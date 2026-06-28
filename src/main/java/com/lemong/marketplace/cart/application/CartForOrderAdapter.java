package com.lemong.marketplace.cart.application;

import com.lemong.marketplace.cart.domain.Cart;
import com.lemong.marketplace.cart.infra.CartRepository;
import com.lemong.marketplace.cart.published.CartForOrder;
import com.lemong.marketplace.cart.published.CartSnapshot;
import org.springframework.stereotype.Component;

/**
 * {@link CartForOrder} published 포트의 cart측 구현. order는 이 클래스를 모르고 포트 인터페이스만
 * 주입받는다 (단방향 의존). 호출자(주문 서비스)의 트랜잭션에 합류한다.
 */
@Component
class CartForOrderAdapter implements CartForOrder {

	private final CartRepository carts;

	CartForOrderAdapter(CartRepository carts) {
		this.carts = carts;
	}

	@Override
	public CartSnapshot read(long cartId) {
		Cart cart = carts.findById(cartId).orElseThrow(() -> new CartNotFoundException(cartId));
		return new CartSnapshot(cart.getId(), cart.getBuyerId(), cart.getStatus().name(), cart.getItems().stream().map(
				it -> new CartSnapshot.Line(it.getProductId(), it.getSellerId(), it.getUnitPrice(), it.getQuantity()))
				.toList());
	}

	@Override
	public void markOrdered(long cartId) {
		// FORCE_INCREMENT 로드로 동시 주문을 직렬화 — 진 쪽은 낙관적 충돌로 실패한다(이중 주문 방지).
		Cart cart = carts.findByIdForUpdate(cartId).orElseThrow(() -> new CartNotFoundException(cartId));
		cart.markOrdered();
	}
}
