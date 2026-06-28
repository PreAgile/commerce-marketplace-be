package com.lemong.marketplace.cart.application;

import com.lemong.marketplace.cart.domain.Cart;
import com.lemong.marketplace.cart.infra.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장바구니 애플리케이션 서비스. 트랜잭션 경계는 메서드 단위(@Transactional)이고, 불변식은 도메인·DB가 강제한다. 여기선
 * load→행위→저장 흐름만 조립한다.
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
		// 동시 담기의 lost update 방지: 루트(Cart) version을 강제 증가시켜 한 카트로의 쓰기를 직렬화한다.
		Cart cart = carts.findByIdForUpdate(cartId).orElseThrow(() -> new CartNotFoundException(cartId));
		cart.addItem(productId, sellerId, unitPrice, quantity);
		return CartView.from(cart); // 같은 트랜잭션 안에서 뷰로 변환
	}

	@Transactional(readOnly = true)
	public CartView getCart(long cartId) {
		Cart cart = carts.findById(cartId).orElseThrow(() -> new CartNotFoundException(cartId));
		return CartView.from(cart);
	}
}
