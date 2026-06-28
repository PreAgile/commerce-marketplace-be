package com.lemong.marketplace.cart.application;

import com.lemong.marketplace.cart.domain.Cart;
import java.util.List;

/**
 * 장바구니 조회 결과(읽기 모델). 트랜잭션 안에서 엔티티→뷰로 변환해 반환하므로 컨트롤러는 지연 로딩 컬렉션을 만지지
 * 않는다(open-in-view=false).
 */
public record CartView(Long cartId, Long buyerId, String status, List<Item> items, long totalAmount) {

	public record Item(Long productId, Long sellerId, long unitPrice, int quantity, long lineAmount) {
	}

	public static CartView from(Cart cart) {
		List<Item> items = cart.getItems().stream().map(it -> new Item(it.getProductId(), it.getSellerId(),
				it.getUnitPrice(), it.getQuantity(), it.getUnitPrice() * it.getQuantity())).toList();
		return new CartView(cart.getId(), cart.getBuyerId(), cart.getStatus().name(), items, cart.total().minor());
	}
}
