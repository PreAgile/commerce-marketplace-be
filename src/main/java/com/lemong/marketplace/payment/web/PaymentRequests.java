package com.lemong.marketplace.payment.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public final class PaymentRequests {

	private PaymentRequests() {
	}

	public record InitiatePayment(@NotNull @Positive Long orderId, @NotBlank String idempotencyKey,
			@NotNull @Positive Long amount) {
	}

	// 부분환불: 어느 주문 라인을 얼마 환불할지 + 멱등 토큰. 결제는 서버가 order_id로 찾는다(S6).
	public record Refund(@NotNull @Positive Long orderLineId, @NotNull @Positive Long amount,
			@NotBlank String idempotencyKey) {
	}
}
