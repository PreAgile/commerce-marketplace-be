package com.lemong.marketplace.cart.application;

import com.lemong.marketplace.cart.domain.Cart;
import com.lemong.marketplace.cart.infra.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장바구니 애플리케이션 서비스. 트랜잭션 경계 = 메서드 단위(@Transactional).
 *
 * <p>불변식은 도메인(Cart/CartItem)이 1차로, DB 제약이 최후로 강제한다. 서비스는 흐름(load→행위→저장)만 조립.
 */
@Service
@Transactional
public class CartService {

    private final CartRepository carts;

    public CartService(CartRepository carts) {
        this.carts = carts;
    }

    public long createCart(long buyerId) {
        Cart cart = carts.save(Cart.createFor(buyerId));
        return cart.getId();
    }

    public CartView addItem(long cartId, long productId, long sellerId, long unitPrice, int quantity) {
        Cart cart = carts.findById(cartId).orElseThrow(() -> new CartNotFoundException(cartId));
        cart.addItem(productId, sellerId, unitPrice, quantity);
        return CartView.from(cart);   // 같은 트랜잭션 안에서 뷰로 변환
    }

    @Transactional(readOnly = true)
    public CartView getCart(long cartId) {
        Cart cart = carts.findById(cartId).orElseThrow(() -> new CartNotFoundException(cartId));
        return CartView.from(cart);
    }
}
