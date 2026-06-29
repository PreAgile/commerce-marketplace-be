package com.lemong.marketplace.shipping;

import static org.assertj.core.api.Assertions.assertThat;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.common.outbox.OutboxDispatcher;
import com.lemong.marketplace.order.domain.Order;
import com.lemong.marketplace.order.domain.OrderLineSpec;
import com.lemong.marketplace.order.infra.OrderRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 결제확정 이벤트 → 셀러별 배송 자동 생성(M3-a, S3)을 실 Postgres로 검증한다. 릴레이 폴링은 끄고(테스트 프로파일에
 * outbox.relay.enabled 없음) 디스패처를 직접 호출해 결정적으로 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ShipmentCreationIT {

	@Autowired
	OutboxDispatcher dispatcher;

	@Autowired
	OrderRepository orders;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE shipment_event, shipment, outbox, order_line, orders RESTART IDENTITY CASCADE")
				.update();
	}

	private long placeMultiSellerOrder(long sellerA, long sellerB) {
		return orders.save(Order.place(1L,
				List.of(new OrderLineSpec(101L, sellerA, 1000L, 1), new OrderLineSpec(202L, sellerB, 2000L, 1))))
				.getId();
	}

	private long appendPaymentConfirmed(long paymentId, long orderId) {
		return jdbc.sql("""
				INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload)
				VALUES ('payment', :pid, 'PaymentConfirmed', CAST(:payload AS jsonb))
				RETURNING id
				""").param("pid", paymentId)
				.param("payload", "{\"paymentId\":%d,\"orderId\":%d,\"amount\":3000,\"pgTransactionId\":\"stub\"}"
						.formatted(paymentId, orderId))
				.query(Long.class).single();
	}

	private int shipmentCount(long orderId) {
		return jdbc.sql("SELECT count(*) FROM shipment WHERE order_id = :oid").param("oid", orderId)
				.query(Integer.class).single();
	}

	private List<Long> sellerIds(long orderId) {
		return jdbc.sql("SELECT seller_id FROM shipment WHERE order_id = :oid").param("oid", orderId).query(Long.class)
				.list();
	}

	private int readyMostRecentCount() {
		return jdbc.sql("SELECT count(*) FROM shipment_event WHERE to_status = 'READY' AND most_recent")
				.query(Integer.class).single();
	}

	private void redeliver(long outboxId) {
		// 릴레이가 발행 마킹 전 크래시 후 재폴링한 at-least-once 상황 재현.
		jdbc.sql("UPDATE outbox SET published_at = NULL WHERE id = :id").param("id", outboxId).update();
		dispatcher.dispatch(outboxId);
	}

	@Nested
	@DisplayName("결제확정 이벤트를 디스패치할 때")
	class Dispatching {

		@Test
		@DisplayName("Given 두 셀러 주문, When PaymentConfirmed를 디스패치하면, Then 셀러별 배송 2건이 READY로 생성되고 이벤트는 발행 표시된다")
		void createsShipmentPerSeller() {
			long orderId = placeMultiSellerOrder(10L, 20L);
			long outboxId = appendPaymentConfirmed(7L, orderId);

			dispatcher.dispatch(outboxId);

			assertThat(shipmentCount(orderId)).isEqualTo(2);
			assertThat(sellerIds(orderId)).containsExactlyInAnyOrder(10L, 20L);
			assertThat(readyMostRecentCount()).isEqualTo(2);
			assertThat(jdbc.sql("SELECT published_at IS NOT NULL FROM outbox WHERE id = :id").param("id", outboxId)
					.query(Boolean.class).single()).isTrue();
		}

		@Test
		@DisplayName("같은 이벤트가 재전달돼도(at-least-once) 셀러당 배송은 1건이다(멱등)")
		void redeliveryIsIdempotent() {
			long orderId = placeMultiSellerOrder(10L, 20L);
			long outboxId = appendPaymentConfirmed(7L, orderId);

			dispatcher.dispatch(outboxId);
			redeliver(outboxId);

			assertThat(shipmentCount(orderId)).isEqualTo(2);
			assertThat(readyMostRecentCount()).isEqualTo(2); // 상태 이력도 중복 적재되지 않는다
		}

		@Test
		@DisplayName("결정론: 같은 이벤트를 N번 재전달해도 셀러당 배송 1건으로 수렴한다")
		void repeatedRedeliveryConverges() {
			long orderId = placeMultiSellerOrder(10L, 20L);
			long outboxId = appendPaymentConfirmed(7L, orderId);

			for (int i = 0; i < 5; i++) {
				redeliver(outboxId);
			}

			assertThat(shipmentCount(orderId)).isEqualTo(2);
			assertThat(readyMostRecentCount()).isEqualTo(2);
		}

		@Test
		@DisplayName("이미 발행된 이벤트를 다시 디스패치하면 아무 일도 하지 않는다(중복 발행 방지)")
		void alreadyPublishedIsNoop() {
			long orderId = placeMultiSellerOrder(10L, 20L);
			long outboxId = appendPaymentConfirmed(7L, orderId);

			dispatcher.dispatch(outboxId);
			dispatcher.dispatch(outboxId); // published_at NOT NULL → FOR UPDATE 재확인에서 빈 결과

			assertThat(shipmentCount(orderId)).isEqualTo(2);
		}
	}
}
