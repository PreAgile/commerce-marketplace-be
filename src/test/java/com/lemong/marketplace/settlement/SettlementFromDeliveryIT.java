package com.lemong.marketplace.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.common.outbox.OutboxDispatcher;
import com.lemong.marketplace.order.domain.Order;
import com.lemong.marketplace.order.domain.OrderLineSpec;
import com.lemong.marketplace.order.infra.OrderRepository;
import com.lemong.marketplace.settlement.application.SettlementService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 배송완료 이벤트 → 셀러별 정산 라인(SALE+/COMMISSION−) 생성(M4-b, S5)을 실 Postgres로 검증한다. 릴레이
 * 폴링은 끄고 디스패처를 직접 호출해 결정적으로 본다(ShipmentCreationIT 선례).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SettlementFromDeliveryIT {

	@Autowired
	OutboxDispatcher dispatcher;

	@Autowired
	SettlementService settlements;

	@Autowired
	OrderRepository orders;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE settlement_line, commission_policy, outbox, order_line, orders "
				+ "RESTART IDENTITY CASCADE").update();
	}

	// 셀러 A(1000)·B(2000) 2줄 멀티셀러 주문.
	private long placeOrder(long sellerA, long sellerB) {
		return orders.save(Order.place(1L,
				List.of(new OrderLineSpec(101L, sellerA, 1000L, 1), new OrderLineSpec(202L, sellerB, 2000L, 1))))
				.getId();
	}

	private long appendDelivered(long shipmentId, long orderId, long sellerId) {
		return jdbc.sql("""
				INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload)
				VALUES ('shipment', :sid, 'ShipmentDelivered', CAST(:payload AS jsonb))
				RETURNING id
				""").param("sid", shipmentId)
				.param("payload",
						("{\"shipmentId\":%d,\"orderId\":%d,\"sellerId\":%d,"
								+ "\"deliveredAt\":\"2026-07-01T00:00:00Z\"}").formatted(shipmentId, orderId, sellerId))
				.query(Long.class).single();
	}

	private void setPolicy(long sellerId, int rateBps) {
		jdbc.sql("INSERT INTO commission_policy (seller_id, rate_bps) VALUES (:s, :r)").param("s", sellerId)
				.param("r", rateBps).update();
	}

	private Long lineAmount(long sellerId, String entryType) {
		return jdbc.sql("SELECT amount_minor FROM settlement_line WHERE seller_id = :s AND entry_type = :t")
				.param("s", sellerId).param("t", entryType).query(Long.class).optional().orElse(null);
	}

	@Test
	@DisplayName("배송완료 소비 → 셀러 매출(+)과 수수료(−) 라인이 정책률로 생성된다")
	void createsSaleAndCommission() {
		long sellerA = 10, sellerB = 20;
		long orderId = placeOrder(sellerA, sellerB);
		setPolicy(sellerA, 1500); // 15%
		long outboxId = appendDelivered(101L, orderId, sellerA);

		dispatcher.dispatch(outboxId);

		assertThat(lineAmount(sellerA, "SALE")).isEqualTo(1000L);
		assertThat(lineAmount(sellerA, "COMMISSION")).isEqualTo(-150L); // 1000 × 15%
		// 이 이벤트는 셀러 A만 정산한다(멀티셀러는 셀러별 배송 이벤트로 각각 온다).
		assertThat(lineAmount(sellerB, "SALE")).isNull();

		// eligible_at은 이벤트의 deliveredAt(구매확정 시각), cycle_id는 아직 미배정(마감은 후속).
		OffsetDateTime eligibleAt = jdbc
				.sql("SELECT eligible_at FROM settlement_line WHERE seller_id = :s AND entry_type = 'SALE'")
				.param("s", sellerA).query(OffsetDateTime.class).single();
		assertThat(eligibleAt).isEqualTo(OffsetDateTime.parse("2026-07-01T00:00:00Z"));
		Long unassigned = jdbc.sql("SELECT count(*) FROM settlement_line WHERE seller_id = :s AND cycle_id IS NULL")
				.param("s", sellerA).query(Long.class).single();
		assertThat(unassigned).isEqualTo(2L);
	}

	@Test
	@DisplayName("commission_policy에 셀러가 없으면 기본율(10%)을 쓴다")
	void usesDefaultRateWhenNoPolicy() {
		long seller = 10;
		long orderId = placeOrder(seller, 20);
		long outboxId = appendDelivered(101L, orderId, seller);

		dispatcher.dispatch(outboxId);

		assertThat(lineAmount(seller, "SALE")).isEqualTo(1000L);
		assertThat(lineAmount(seller, "COMMISSION")).isEqualTo(-100L); // 기본 10%
	}

	@Test
	@DisplayName("같은 배송완료 이벤트를 두 번 소비해도 라인은 한 벌만 남는다(멱등)")
	void idempotentOnRedelivery() {
		long seller = 10;
		long orderId = placeOrder(seller, 20);
		OffsetDateTime deliveredAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");

		// at-least-once: 같은 이벤트(source_event_id=shipment:101)가 핸들러에 두 번 도달한 상황을 직접 모사.
		settlements.recordSaleForDelivery(101L, orderId, seller, deliveredAt);
		settlements.recordSaleForDelivery(101L, orderId, seller, deliveredAt);

		Long count = jdbc.sql("SELECT count(*) FROM settlement_line WHERE seller_id = :s").param("s", seller)
				.query(Long.class).single();
		assertThat(count).isEqualTo(2L); // SALE 1 + COMMISSION 1, 중복 없음
	}

	@Test
	@DisplayName("주문이 아직 안 보이면(셀러 몫 0) 아무 라인도 만들지 않는다")
	void noLinesWhenOrderInvisible() {
		long seller = 10;
		long missingOrderId = 999_999L;

		settlements.recordSaleForDelivery(101L, missingOrderId, seller, OffsetDateTime.parse("2026-07-01T00:00:00Z"));

		Long count = jdbc.sql("SELECT count(*) FROM settlement_line").query(Long.class).single();
		assertThat(count).isZero();
	}
}
