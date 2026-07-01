package com.lemong.marketplace.shipping.application;

import com.lemong.marketplace.order.published.OrderForShipping;
import com.lemong.marketplace.shipping.application.ShipmentView.Transition;
import com.lemong.marketplace.shipping.domain.ShipmentStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제확정 이벤트로 셀러별 배송을 생성하고(S3), 이후 상태 전이를 기록한다(S4).
 *
 * <p>
 * 생성 멱등은 try-catch가 아니라
 * {@code ON CONFLICT (source_event_id, seller_id) DO NOTHING}으로 강제한다
 * (payment.initiate 선례, ADR-013 PR#17): 충돌을 예외가 아닌 no-op으로 흡수해 at-least-once
 * 재전달을 안전하게 흘려보낸다. RETURNING으로 "이번에 새로 만든 배송"만 식별해 그때만 초기 상태 이벤트(READY)를 쌓는다.
 *
 * <p>
 * 전이는 {@link ShipmentStatus} 화이트리스트로 합법성을 막고, 한 트랜잭션에서 직전 most_recent를 내리고 새 행을
 * 올린다(append-only). 같은 배송에 동시 전이가 몰려도 shipment 행 {@code FOR UPDATE}로 직렬화한다 —
 * most_recent 부분 UNIQUE는 그 최후 보루.
 */
@Service
@Transactional
public class ShipmentService {

	private final OrderForShipping orders;
	private final JdbcClient jdbc;

	public ShipmentService(OrderForShipping orders, JdbcClient jdbc) {
		this.orders = orders;
		this.jdbc = jdbc;
	}

	public void createForPaymentConfirmed(long orderId, String sourceEventId) {
		for (long sellerId : orders.sellerIdsForOrder(orderId)) {
			Long shipmentId = jdbc.sql("""
					INSERT INTO shipment (order_id, seller_id, source_event_id, status)
					VALUES (:orderId, :seller, :evid, 'READY')
					ON CONFLICT (source_event_id, seller_id) DO NOTHING
					RETURNING id
					""").param("orderId", orderId).param("seller", sellerId).param("evid", sourceEventId)
					.query(Long.class).optional().orElse(null);
			if (shipmentId != null) {
				// 새로 만든 배송만 최초 상태 이력을 쌓는다(append-only, 현재 상태 = most_recent). 재수신 시엔 건너뛴다.
				jdbc.sql(
						"""
								INSERT INTO shipment_event (shipment_id, from_status, to_status, most_recent, sort_key, occurred_at)
								VALUES (:sid, NULL, 'READY', TRUE, 0, now())
								""")
						.param("sid", shipmentId).update();
			}
		}
	}

	/**
	 * 배송 상태를 {@code target}으로 전이하고 갱신된 배송 스냅샷을 반환한다. occurredAt이 null이면 발생 시각을 현재로
	 * 본다(택배사 타임스탬프가 없는 수동 전이).
	 *
	 * <p>
	 * 응답을 잠금을 쥔 이 트랜잭션 안에서 조립한다(read-your-writes). 커밋 후 별도 조회로 만들면 그 사이 끼어든 다른 전이의
	 * "더 나중 상태"가 반환될 수 있다(PR #20 리뷰).
	 *
	 * @throws ShipmentNotFoundException
	 *             배송이 없을 때(404)
	 * @throws IllegalStateException
	 *             화이트리스트가 막는 불법 전이일 때(409)
	 */
	public ShipmentView recordTransition(long shipmentId, ShipmentStatus target, OffsetDateTime occurredAt) {
		// 핫로우 직렬화: 같은 배송에 동시 전이가 오면 한 번에 하나만 통과시킨다. 잠금을 쥔 뒤 상태를 읽어야
		// READ COMMITTED에서 직전 전이의 커밋분을 본다(잠금 대기 = 앞 트랜잭션 커밋 완료).
		boolean exists = jdbc.sql("SELECT 1 FROM shipment WHERE id = :id FOR UPDATE").param("id", shipmentId)
				.query(Integer.class).optional().isPresent();
		if (!exists) {
			throw new ShipmentNotFoundException(shipmentId);
		}

		Current current = jdbc.sql("""
				SELECT to_status, sort_key FROM shipment_event
				WHERE shipment_id = :id AND most_recent
				""").param("id", shipmentId)
				.query((rs, n) -> new Current(ShipmentStatus.valueOf(rs.getString("to_status")), rs.getInt("sort_key")))
				.single();

		// 전이 멱등키(source_event_id)는 아직 없다: 이 경로는 동기 REST API라 재전달 원천이 없다. at-least-once인
		// 택배사 추적 콜백을 붙이는 슬라이스에서 멱등키를 넣는다 — 그 전엔 같은 이벤트 재수신이 자가전이 409로 보일 수 있다(PR #20
		// 리뷰).
		if (!current.status().canTransitionTo(target)) {
			throw new IllegalStateException("illegal shipment transition: " + current.status() + " -> " + target
					+ " (shipment " + shipmentId + ")");
		}

		jdbc.sql("UPDATE shipment_event SET most_recent = FALSE WHERE shipment_id = :id AND most_recent")
				.param("id", shipmentId).update();
		jdbc.sql("""
				INSERT INTO shipment_event (shipment_id, from_status, to_status, most_recent, sort_key, occurred_at)
				VALUES (:id, :from, :to, TRUE, :sk, COALESCE(:at, now()))
				""").param("id", shipmentId).param("from", current.status().name()).param("to", target.name())
				.param("sk", current.sortKey() + 1).param("at", occurredAt).update();
		jdbc.sql("UPDATE shipment SET status = :to WHERE id = :id").param("to", target.name()).param("id", shipmentId)
				.update();

		return buildView(shipmentId);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ShipmentView getShipment(long shipmentId) {
		return buildView(shipmentId);
	}

	private ShipmentView buildView(long shipmentId) {
		ShipmentView head = jdbc.sql("SELECT order_id, seller_id, status FROM shipment WHERE id = :id")
				.param("id", shipmentId)
				.query((rs, n) -> new ShipmentView(shipmentId, rs.getLong("order_id"), rs.getLong("seller_id"),
						ShipmentStatus.valueOf(rs.getString("status")), List.of()))
				.optional().orElseThrow(() -> new ShipmentNotFoundException(shipmentId));

		List<Transition> history = jdbc.sql("""
				SELECT from_status, to_status, occurred_at FROM shipment_event
				WHERE shipment_id = :id ORDER BY sort_key
				""").param("id", shipmentId).query((rs, n) -> new Transition(
				rs.getString("from_status") == null ? null : ShipmentStatus.valueOf(rs.getString("from_status")),
				ShipmentStatus.valueOf(rs.getString("to_status")), rs.getObject("occurred_at", OffsetDateTime.class)))
				.list();

		return new ShipmentView(head.id(), head.orderId(), head.sellerId(), head.status(), history);
	}

	private record Current(ShipmentStatus status, int sortKey) {
	}
}
