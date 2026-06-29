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
}
