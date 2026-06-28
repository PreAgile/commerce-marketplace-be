package com.lemong.marketplace.cart.application;

import com.lemong.marketplace.cart.domain.Cart;
import java.util.List;

/**
 * 장바구니 조회 결과(읽기 모델). 트랜잭션 안에서 엔티티→뷰로 변환해 반환하므로 컨트롤러는 지연 로딩 컬렉션을 만지지
 * 않는다(open-in-view=false).
 */
public record CartView(Long cartId, long buyerId, String status, List<Item> items, long totalAmount) {

	public record Item(long productId, long sellerId, long unitPrice, int quantity, long lineAmount) {
	}

	public static CartView from(Cart cart) {
		// lineAmount는 도메인 lineAmount()(Money.times, 오버플로 fail-loud)를 그대로 쓴다 —
		// 뷰에서 raw 곱을 다시 하면 같은 값이 조용히 오버플로해 도메인 total()과 갈라진다.
		List<Item> items = cart.getItems().stream().map(it -> new Item(it.getProductId(), it.getSellerId(),
				it.getUnitPrice(), it.getQuantity(), it.lineAmount().minor())).toList();
		return new CartView(cart.getId(), cart.getBuyerId(), cart.getStatus().name(), items, cart.total().minor());
	}
}
