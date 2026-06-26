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
import java.util.ArrayList;
import java.util.List;

/**
 * 장바구니 애그리거트 루트. 항목 추가/조회는 반드시 이 루트를 통해서만(애그리거트 캡슐화).
 *
 * <p>외부 컨텍스트(주문)의 엔티티를 직접 참조하지 않는다 — 주문 전환은 주문 컨텍스트가 cart를 *읽어* 수행한다.
 * product_id/seller_id는 외부 도메인으로의 값(논리 참조)일 뿐 객체로 끌고 오지 않는다.
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

    /**
     * 상품을 담는다(upsert). 오퍼의 단위는 <b>(상품, 셀러)</b> — 마켓플레이스에선 같은 상품을 여러 셀러가
     * 판다. 같은 (상품, 셀러)면 수량을 더하고, 셀러가 다르면 별도 라인으로 둔다(멀티셀러 핵심).
     * {@code uq_cartitem_offer}(cart_id, product_id, seller_id)와 정합.
     *
     * <p>같은 (상품, 셀러)를 다른 단가로 재담기하면 기존 가격 스냅샷(담은 시점 가격)을 유지하고 수량만 더한다.
     */
    public void addItem(long productId, long sellerId, long unitPrice, int quantity) {
        ensureActive();
        findOffer(productId, sellerId)
                .ifPresentOrElse(
                        existing -> existing.addQuantity(quantity),
                        () -> items.add(CartItem.of(this, productId, sellerId, unitPrice, quantity)));
    }

    /** 담긴 항목 전체 합계. */
    public Money total() {
        return items.stream().map(CartItem::lineAmount).reduce(Money.ZERO, Money::plus);
    }

    private void ensureActive() {
        if (status != CartStatus.ACTIVE) {
            throw new IllegalStateException("cart " + id + " is not ACTIVE (status=" + status + ")");
        }
    }

    private java.util.Optional<CartItem> findOffer(long productId, long sellerId) {
        return items.stream()
                .filter(it -> it.getProductId() == productId && it.getSellerId() == sellerId)
                .findFirst();
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
