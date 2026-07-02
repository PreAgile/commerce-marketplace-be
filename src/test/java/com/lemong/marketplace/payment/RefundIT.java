package com.lemong.marketplace.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.payment.application.PaymentNotFoundException;
import com.lemong.marketplace.payment.application.RefundService;
import com.lemong.marketplace.payment.application.RefundView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 부분환불(M5-a, S6)을 실 Postgres로 검증한다: 환불이 원장에 쌓이고 누적환불·환불가능액이 SUM으로 도출되며, 결제금액
 * 초과·미결제 환불이 거부되고, 같은 토큰 재요청이 멱등하게 no-op임을 본다. PaymentRefunded 이벤트도 확인.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RefundIT {

	@Autowired
	RefundService refunds;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE refund, outbox, payment RESTART IDENTITY CASCADE").update();
	}

	private long seedPayment(long orderId, long paid, String status) {
		return jdbc.sql("""
				INSERT INTO payment (order_id, idempotency_key, paid_amount, status)
				VALUES (:oid, :key, :paid, :status) RETURNING id
				""").param("oid", orderId).param("key", "pay-" + orderId).param("paid", paid).param("status", status)
				.query(Long.class).single();
	}

	// 이벤트 aggregate는 refund별로 분리되므로(refundId) 결제 단위 카운트는 event_type로 센다.
	// @BeforeEach가
	// outbox를 비워 이 테스트의 환불 이벤트만 남는다.
	private long refundedEventCount() {
		return jdbc.sql("SELECT count(*) FROM outbox WHERE event_type = 'PaymentRefunded'").query(Long.class).single();
	}

	@Test
	@DisplayName("부분환불 → 원장에 쌓이고 누적환불·환불가능액이 SUM으로 도출된다")
	void partialRefundAccrues() {
		long orderId = 100;
		long pid = seedPayment(orderId, 1000, "PAID");

		RefundView v1 = refunds.refund(orderId, 10L, 400, "r-1");
		assertThat(v1.refundedMinor()).isEqualTo(400);
		assertThat(v1.refundableMinor()).isEqualTo(600);

		RefundView v2 = refunds.refund(orderId, 10L, 300, "r-2");
		assertThat(v2.refundedMinor()).isEqualTo(700);
		assertThat(v2.refundableMinor()).isEqualTo(300);

		Long rows = jdbc.sql("SELECT count(*) FROM refund WHERE payment_id = :pid").param("pid", pid).query(Long.class)
				.single();
		assertThat(rows).isEqualTo(2L);
		assertThat(refundedEventCount()).isEqualTo(2L); // 환불마다 PaymentRefunded 1건
	}

	@Test
	@DisplayName("누적환불이 결제금액을 초과하면 거부된다(트리거)")
	void overRefundRejected() {
		long orderId = 101;
		seedPayment(orderId, 1000, "PAID");
		refunds.refund(orderId, 10L, 700, "r-a");

		assertThatThrownBy(() -> refunds.refund(orderId, 10L, 400, "r-b")) // 700 + 400 > 1000
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("PAID가 아닌 결제는 환불할 수 없다(409)")
	void refundOnNonPaidRejected() {
		long orderId = 102;
		seedPayment(orderId, 1000, "PENDING");

		assertThatThrownBy(() -> refunds.refund(orderId, 10L, 100, "r-x")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("결제 없는 주문 환불은 404")
	void refundOnMissingPayment() {
		assertThatThrownBy(() -> refunds.refund(999_999L, 10L, 100, "r-none"))
				.isInstanceOf(PaymentNotFoundException.class);
	}

	@Test
	@DisplayName("같은 토큰으로 두 번 환불해도 원장·이벤트는 한 벌만(멱등)")
	void idempotentOnSameToken() {
		long orderId = 103;
		long pid = seedPayment(orderId, 1000, "PAID");

		refunds.refund(orderId, 10L, 400, "r-same");
		RefundView again = refunds.refund(orderId, 10L, 400, "r-same"); // 재요청

		assertThat(again.refundedMinor()).isEqualTo(400); // 800이 아님 — 두 번째는 no-op
		Long rows = jdbc.sql("SELECT count(*) FROM refund WHERE payment_id = :pid").param("pid", pid).query(Long.class)
				.single();
		assertThat(rows).isEqualTo(1L);
		assertThat(refundedEventCount()).isEqualTo(1L);
	}
}
