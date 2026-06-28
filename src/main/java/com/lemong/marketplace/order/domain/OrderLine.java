package com.lemong.marketplace.order.domain;

import com.lemong.marketplace.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 주문 라인 — Order 애그리거트 내부 엔티티. 생성은 {@link Order#place}를 통해서만(of는
 * package-private).
 *
 * <p>
 * line_amount는 담을 때 unit_price×quantity로 한 번 굳힌 스냅샷이며 DB CHECK가 계산식을 최후로 보장한다.
 * seller_id가 라인마다 다를 수 있는 것이 멀티셀러의 토대 — 정산이 이 seller_id로 라인을 모은다.
 */
@Entity
@Table(name = "order_line")
public class OrderLine {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_id", nullable = false)
	private Order order;

	@Column(name = "seller_id", nullable = false)
	private Long sellerId;

	@Column(name = "product_id", nullable = false)
	private Long productId;

	@Column(nullable = false)
	private int quantity;

	@Column(name = "unit_price", nullable = false)
	private long unitPrice;

	@Column(name = "line_amount", nullable = false)
	private long lineAmount;

	protected OrderLine() {
		// JPA
	}

	private OrderLine(Order order, long productId, long sellerId, long unitPrice, int quantity) {
		this.order = order;
		this.productId = productId;
		this.sellerId = sellerId;
		this.unitPrice = unitPrice;
		this.quantity = quantity;
		this.lineAmount = Money.of(unitPrice).times(quantity).minor(); // 오버플로는 Money가 fail-loud
	}

	static OrderLine of(Order order, long productId, long sellerId, long unitPrice, int quantity) {
		requirePositiveId(productId, "productId");
		requirePositiveId(sellerId, "sellerId");
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be > 0, but was " + quantity);
		}
		if (unitPrice < 0) {
			throw new IllegalArgumentException("unitPrice must be >= 0, but was " + unitPrice);
		}
		return new OrderLine(order, productId, sellerId, unitPrice, quantity);
	}

	Money lineAmount() {
		return Money.of(lineAmount);
	}

	private static void requirePositiveId(long id, String name) {
		if (id <= 0) {
			throw new IllegalArgumentException(name + " must be > 0, but was " + id);
		}
	}

	public Long getId() {
		return id;
	}

	public Long getProductId() {
		return productId;
	}

	public Long getSellerId() {
		return sellerId;
	}

	public int getQuantity() {
		return quantity;
	}

	public long getUnitPrice() {
		return unitPrice;
	}

	public long getLineAmount() {
		return lineAmount;
	}
}
