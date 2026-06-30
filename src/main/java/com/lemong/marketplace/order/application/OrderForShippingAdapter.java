package com.lemong.marketplace.order.application;

import com.lemong.marketplace.order.domain.OrderLine;
import com.lemong.marketplace.order.infra.OrderRepository;
import com.lemong.marketplace.order.published.OrderForShipping;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link OrderForShipping} published 포트의 order측 구현. shipping은 이 클래스를 모르고 포트만
 * 주입받는다 (단방향 의존). 주문이 없으면 빈 목록을 돌려준다 — at-least-once 이벤트가 잠깐 앞선 경우라도 릴레이를 죽이지
 * 않는다.
 */
@Component
class OrderForShippingAdapter implements OrderForShipping {

	private final OrderRepository orders;

	OrderForShippingAdapter(OrderRepository orders) {
		this.orders = orders;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Long> sellerIdsForOrder(long orderId) {
		return orders.findById(orderId).map(o -> o.getLines().stream().map(OrderLine::getSellerId).distinct().toList())
				.orElse(List.of());
	}
}
