package com.lemong.marketplace.order.domain;

import com.lemong.marketplace.common.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * 주문 애그리거트 루트. 멀티셀러 — 한 주문에 서로 다른 seller_id 라인이 공존한다.
 *
 * <p>
 * total_amount는 confirm 시 PG 청구액과 대조할 load-bearing 값이라 생성 시점에 Σ라인으로 굳힌다. "total
 * == Σ라인"은 DB의 DEFERRABLE 제약 트리거가 커밋 시점에 최후로 보장한다.
 */
@Entity
@Table(name = "orders")
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "buyer_id", nullable = false)
	private Long buyerId;

	@Column(name = "total_amount", nullable = false)
	private long totalAmount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus status;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<OrderLine> lines = new ArrayList<>();

	protected Order() {
		// JPA
	}

	private Order(long buyerId) {
		this.buyerId = buyerId;
		this.status = OrderStatus.CREATED;
	}

	public static Order place(long buyerId, List<OrderLineSpec> specs) {
		if (buyerId <= 0) {
			throw new IllegalArgumentException("buyerId must be > 0, but was " + buyerId);
		}
		if (specs.isEmpty()) {
			throw new IllegalArgumentException("order requires at least one line");
		}
		Order order = new Order(buyerId);
		specs.forEach(
				s -> order.lines.add(OrderLine.of(order, s.productId(), s.sellerId(), s.unitPrice(), s.quantity())));
		order.totalAmount = order.lines.stream().map(OrderLine::lineAmount).reduce(Money.ZERO, Money::plus).minor();
		return order;
	}

	public Money total() {
		return Money.of(totalAmount);
	}

	public Long getId() {
		return id;
	}

	public Long getBuyerId() {
		return buyerId;
	}

	public OrderStatus getStatus() {
		return status;
	}

	public List<OrderLine> getLines() {
		return List.copyOf(lines);
	}
}
