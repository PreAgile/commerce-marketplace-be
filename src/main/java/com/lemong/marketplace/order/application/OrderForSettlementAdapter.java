package com.lemong.marketplace.order.application;

import com.lemong.marketplace.order.domain.OrderLine;
import com.lemong.marketplace.order.infra.OrderRepository;
import com.lemong.marketplace.order.published.OrderForSettlement;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link OrderForSettlement} published 포트의 order측 구현. settlement는 이 클래스를 모르고
 * 포트만 주입받는다(단방향). 주문이 없으면 0을 돌려준다 — at-least-once 이벤트가 잠깐 앞선 경우라도 릴레이를 죽이지 않는다
 * (OrderForShippingAdapter 선례).
 */
@Component
class OrderForSettlementAdapter implements OrderForSettlement {

	private final OrderRepository orders;

	OrderForSettlementAdapter(OrderRepository orders) {
		this.orders = orders;
	}

	@Override
	@Transactional(readOnly = true)
	public long sellerAmountForOrder(long orderId, long sellerId) {
		return orders.findById(orderId).stream().flatMap(o -> o.getLines().stream())
				.filter(l -> l.getSellerId() == sellerId).mapToLong(OrderLine::getLineAmount).sum();
	}
}
