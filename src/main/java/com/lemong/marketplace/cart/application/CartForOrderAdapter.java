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
	public CartSnapshot consumeForOrder(long cartId) {
		// 락으로 한 번 로드 → 닫기 → 그 잠긴 인스턴스에서 스냅샷 추출. 스냅샷과 닫힌 카트가 같은 상태라 stale 주문이 없고,
		// FORCE_INCREMENT가 동시 수정/이중 주문을 version 충돌로 직렬화한다. markOrdered가 먼저라 이미 ORDERED면
		// 즉시 거부.
		Cart cart = carts.findByIdForUpdate(cartId).orElseThrow(() -> new CartNotFoundException(cartId));
		cart.markOrdered();
		return new CartSnapshot(cart.getId(), cart.getBuyerId(), cart.getStatus().name(), cart.getItems().stream().map(
				it -> new CartSnapshot.Line(it.getProductId(), it.getSellerId(), it.getUnitPrice(), it.getQuantity()))
				.toList());
	}
}
