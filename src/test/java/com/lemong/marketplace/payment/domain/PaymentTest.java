package com.lemong.marketplace.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

	@Test
	@DisplayName("선점하면 PENDING으로 시작한다")
	void initiateStartsPending() {
		Payment payment = Payment.initiate(1L, "idem-1", 10_000L);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(payment.isPaid()).isFalse();
	}

	@Test
	@DisplayName("PENDING에서 확정하면 PAID가 된다")
	void markPaidFromPending() {
		Payment payment = Payment.initiate(1L, "idem-1", 10_000L);

		payment.markPaid();

		assertThat(payment.isPaid()).isTrue();
	}

	@Test
	@DisplayName("이미 PAID인 결제를 또 확정하려 하면 거부된다(역전이/이중확정 금지)")
	void markPaidTwiceRejected() {
		Payment payment = Payment.initiate(1L, "idem-1", 10_000L);
		payment.markPaid();

		assertThatThrownBy(payment::markPaid).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("금액이 0 이하면 선점이 거부된다")
	void nonPositiveAmountRejected() {
		assertThatThrownBy(() -> Payment.initiate(1L, "idem-1", 0L)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("멱등키가 비어 있으면 선점이 거부된다")
	void blankIdempotencyKeyRejected() {
		assertThatThrownBy(() -> Payment.initiate(1L, " ", 10_000L)).isInstanceOf(IllegalArgumentException.class);
	}
}
