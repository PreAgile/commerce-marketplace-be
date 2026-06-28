package com.lemong.marketplace.order.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public final class OrderRequests {

	private OrderRequests() {
	}

	public record PlaceOrder(@NotNull @Positive Long cartId) {
	}
}
