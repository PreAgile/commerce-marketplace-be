package com.lemong.marketplace.cart.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** 장바구니 API 요청 DTO. Bean Validation이 1차로 거르고 도메인·DB가 받친다. */
public final class CartRequests {

	private CartRequests() {
	}

	public record CreateCart(@NotNull @Positive Long buyerId) {
	}

	public record AddItem(@NotNull @Positive Long productId, @NotNull @Positive Long sellerId,
			@NotNull @PositiveOrZero Long unitPrice, @NotNull @Positive Integer quantity) {
	}
}
