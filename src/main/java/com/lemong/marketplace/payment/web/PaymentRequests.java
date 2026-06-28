package com.lemong.marketplace.payment.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 결제 API 요청 DTO. Bean Validation이 1차로 거르고 도메인·DB가 받친다. */
public final class PaymentRequests {

	private PaymentRequests() {
	}

	public record InitiatePayment(@NotNull @Positive Long orderId, @NotBlank String idempotencyKey,
			@NotNull @Positive Long amount) {
	}
}
