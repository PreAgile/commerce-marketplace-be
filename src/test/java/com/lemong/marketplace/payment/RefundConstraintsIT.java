package com.lemong.marketplace.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lemong.marketplace.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 환불 원장 불변식이 <b>DB 제약으로</b> 강제됨을 실 Postgres로 검증한다(M5-a). 누적환불 상한(≤ 결제금액)은
 * {@code trg_refund_within_paid} 트리거가, 하한(&gt; 0)은 CHECK가, 멱등은 UNIQUE가 막는다 —
 * payment.refunded_amount 컬럼을 걷어내고 원장으로 옮긴 자리(V2 TODO·ADR-019).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RefundConstraintsIT {

	@Autowired
	JdbcClient jdbc;

	private long paymentId;

	@BeforeEach
	void seedPaidPayment() {
		jdbc.sql("TRUNCATE TABLE refund, payment RESTART IDENTITY CASCADE").update();
		paymentId = jdbc.sql("""
				INSERT INTO payment (order_id, idempotency_key, paid_amount, status)
				VALUES (1, 'pay-1', 1000, 'PAID') RETURNING id
				""").query(Long.class).single();
	}

	private int insertRefund(long pid, long amount, String evid) {
		return jdbc.sql("""
				INSERT INTO refund (payment_id, order_line_id, amount_minor, source_event_id)
				VALUES (:pid, 10, :amt, :evid)
				""").param("pid", pid).param("amt", amount).param("evid", evid).update();
	}

	@Test
	@DisplayName("결제금액 이내 환불은 INSERT된다")
	void validRefundInserts() {
		assertThat(insertRefund(paymentId, 300, "r-1")).isEqualTo(1);
	}

	@Test
	@DisplayName("0원·음수 환불은 CHECK로 거부된다(하한)")
	void nonPositiveRejected() {
		assertThatThrownBy(() -> insertRefund(paymentId, 0, "r-zero"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertRefund(paymentId, -1, "r-neg"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("누적환불이 결제금액과 같아지는 경계까지는 허용된다(≤)")
	void cumulativeUpToPaidAllowed() {
		assertThat(insertRefund(paymentId, 600, "r-a")).isEqualTo(1);
		assertThat(insertRefund(paymentId, 400, "r-b")).isEqualTo(1); // 600 + 400 == 1000
	}

	@Test
	@DisplayName("누적환불이 결제금액을 초과하면 트리거로 거부된다(상한)")
	void cumulativeExceedingPaidRejected() {
		insertRefund(paymentId, 600, "r-a");
		assertThatThrownBy(() -> insertRefund(paymentId, 500, "r-c")) // 600 + 500 > 1000
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("같은 멱등키(source_event_id) 재적재는 UNIQUE로 거부된다")
	void duplicateEventRejected() {
		insertRefund(paymentId, 100, "r-dup");
		assertThatThrownBy(() -> insertRefund(paymentId, 100, "r-dup"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("존재하지 않는 결제에 대한 환불은 거부된다(FK/트리거)")
	void unknownPaymentRejected() {
		assertThatThrownBy(() -> insertRefund(999_999L, 100, "r-nopay"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
