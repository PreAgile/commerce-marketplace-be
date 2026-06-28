package com.lemong.marketplace.order.application;

import com.lemong.marketplace.cart.published.CartForOrder;
import com.lemong.marketplace.cart.published.CartSnapshot;
import com.lemong.marketplace.order.domain.Order;
import com.lemong.marketplace.order.domain.OrderLineSpec;
import com.lemong.marketplace.order.infra.OrderRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 생성 — 골든 시나리오 S1 후반. 카트의 published 스냅샷을 읽어 주문으로 굳히고 카트를 닫는다.
 *
 * <p>
 * cart 내부(엔티티·테이블)는 모른 채 {@link CartForOrder} 포트로만 협력한다(BC 단방향).
 * read→save→markOrdered가 한 트랜잭션이라 카트 닫기 실패(이미 ORDERED/동시 주문 충돌) 시 주문도 함께 롤백된다 —
 * 부분 전환이 남지 않는다.
 */
@Service
@Transactional
public class PlaceOrderService {

	private final OrderRepository orders;
	private final CartForOrder cart;

	public PlaceOrderService(OrderRepository orders, CartForOrder cart) {
		this.orders = orders;
		this.cart = cart;
	}

	public long place(long cartId) {
		CartSnapshot snapshot = cart.read(cartId);
		List<OrderLineSpec> specs = snapshot.lines().stream()
				.map(l -> new OrderLineSpec(l.productId(), l.sellerId(), l.unitPrice(), l.quantity())).toList();
		Order order = Order.place(snapshot.buyerId(), specs); // 빈 카트면 IllegalArgumentException → 400
		long orderId = orders.save(order).getId();
		cart.markOrdered(cartId); // 이미 ORDERED면 409, 동시 주문이면 낙관적 충돌 → 둘 다 롤백
		return orderId;
	}

	@Transactional(readOnly = true)
	public OrderView getOrder(long orderId) {
		return OrderView.from(orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId)));
	}
}
