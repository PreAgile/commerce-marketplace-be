package com.lemong.marketplace.order.application;

import com.lemong.marketplace.order.domain.Order;
import java.util.List;

/** 주문 조회 결과(읽기 모델). 트랜잭션 안에서 엔티티→뷰로 변환해 지연 로딩을 컨트롤러로 새지 않게 한다. */
public record OrderView(Long orderId, Long buyerId, String status, List<Item> items, long totalAmount) {

	public record Item(Long productId, Long sellerId, long unitPrice, int quantity, long lineAmount) {
	}

	public static OrderView from(Order order) {
		List<Item> items = order.getLines().stream().map(
				l -> new Item(l.getProductId(), l.getSellerId(), l.getUnitPrice(), l.getQuantity(), l.getLineAmount()))
				.toList();
		return new OrderView(order.getId(), order.getBuyerId(), order.getStatus().name(), items, order.total().minor());
	}
}
