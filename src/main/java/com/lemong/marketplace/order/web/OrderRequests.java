package com.lemong.marketplace.order.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 주문 API 요청 DTO. Bean Validation이 1차로 거르고 도메인·DB가 받친다. */
public final class OrderRequests {

	private OrderRequests() {
	}

	public record PlaceOrder(@NotNull @Positive Long cartId) {
	}
}
