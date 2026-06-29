package com.lemong.marketplace.shipping.application;

import com.lemong.marketplace.order.published.OrderForShipping;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제확정 이벤트로 셀러별 배송을 생성한다(S3). 멱등 — 같은 이벤트를 재수신/따닥해도 셀러당 배송은 단 1건이다.
 *
 * <p>
 * 멱등을 try-catch가 아니라
 * {@code ON CONFLICT (source_event_id, seller_id) DO NOTHING}으로 강제한다
 * (payment.initiate 선례, ADR-013 PR#17): 충돌을 예외가 아닌 no-op으로 흡수해 at-least-once
 * 재전달을 안전하게 흘려보낸다. RETURNING으로 "이번에 새로 만든 배송"만 식별해 그때만 초기 상태 이벤트(READY)를 쌓는다.
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
}
