package com.lemong.marketplace.cart.domain;

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
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 장바구니 애그리거트 루트. product_id·seller_id는 외부 도메인의 논리 참조라 객체로 끌어오지 않는다 (주문 전환은 주문
 * 컨텍스트가 cart를 읽어서 한다).
 */
@Entity
@Table(name = "cart")
public class Cart {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "buyer_id", nullable = false)
	private Long buyerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CartStatus status;

	@OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<CartItem> items = new ArrayList<>();

	// 자식(CartItem) 수량만 바뀌어도 루트 version이 올라가도록 OPTIMISTIC_FORCE_INCREMENT로 로드한다
	// (CartRepository#findByIdForUpdate). 안 그러면 동시 담기에서 수량 누적이 유실된다.
	@Version
	@Column(nullable = false)
	private long version;

	protected Cart() {
		// JPA
	}

	private Cart(long buyerId) {
		this.buyerId = buyerId;
		this.status = CartStatus.ACTIVE;
	}

	public static Cart createFor(long buyerId) {
		if (buyerId <= 0) {
			throw new IllegalArgumentException("buyerId must be > 0, but was " + buyerId);
		}
		return new Cart(buyerId);
	}

	// 오퍼 단위는 (상품, 셀러) — 같은 상품을 여러 셀러가 판다. 같은 (상품,셀러)면 수량 누적, 셀러가 다르면 별도 라인.
	// 재담기 시 단가는 처음 담은 시점 스냅샷을 유지한다. uq_cartitem_offer(cart_id, product_id,
	// seller_id)와 정합.
	public void addItem(long productId, long sellerId, long unitPrice, int quantity) {
		ensureActive();
		findOffer(productId, sellerId).ifPresentOrElse(existing -> existing.addQuantity(quantity),
				() -> items.add(CartItem.of(this, productId, sellerId, unitPrice, quantity)));
	}

	public Money total() {
		return items.stream().map(CartItem::lineAmount).reduce(Money.ZERO, Money::plus);
	}

	private void ensureActive() {
		if (status != CartStatus.ACTIVE) {
			throw new IllegalStateException("cart " + id + " is not ACTIVE (status=" + status + ")");
		}
	}

	// 카트당 오퍼 수는 작아 선형 스캔으로 충분 — 맵 인덱싱은 과설계.
	private Optional<CartItem> findOffer(long productId, long sellerId) {
		return items.stream().filter(it -> it.getProductId() == productId && it.getSellerId() == sellerId).findFirst();
	}

	public Long getId() {
		return id;
	}

	public Long getBuyerId() {
		return buyerId;
	}

	public CartStatus getStatus() {
		return status;
	}

	public List<CartItem> getItems() {
		return List.copyOf(items);
	}
}
