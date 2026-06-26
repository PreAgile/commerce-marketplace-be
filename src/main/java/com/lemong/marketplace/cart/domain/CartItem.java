package com.lemong.marketplace.cart.domain;

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
 * 장바구니 항목 — Cart 애그리거트 내부 엔티티. 외부에서 직접 생성하지 않고 {@link Cart#addItem}을 통해서만 만든다.
 *
 * <p>불변식(수량&gt;0, 단가&ge;0)은 생성·변경 시점에 도메인이 1차로 거르고, DB CHECK가 최후로 보장한다.
 * created_at 컬럼은 DB DEFAULT가 채우므로 매핑하지 않는다(ddl-auto=validate는 미매핑 컬럼을 문제삼지 않음).
 */
@Entity
@Table(name = "cart_item")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    protected CartItem() {
        // JPA
    }

    private CartItem(Cart cart, long productId, long sellerId, long unitPrice, int quantity) {
        this.cart = cart;
        this.productId = productId;
        this.sellerId = sellerId;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    static CartItem of(Cart cart, long productId, long sellerId, long unitPrice, int quantity) {
        requirePositive(quantity);
        requireNonNegative(unitPrice);
        return new CartItem(cart, productId, sellerId, unitPrice, quantity);
    }

    /** 같은 상품을 또 담으면 수량을 더한다(담기 = 누적). */
    void addQuantity(int delta) {
        requirePositive(delta);
        this.quantity += delta;
    }

    Money lineAmount() {
        return Money.of(unitPrice).times(quantity);
    }

    private static void requirePositive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("quantity must be > 0, but was " + value);
        }
    }

    private static void requireNonNegative(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("unitPrice must be >= 0, but was " + value);
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

    public long getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }
}
