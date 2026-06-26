package com.lemong.marketplace.cart.application;

import com.lemong.marketplace.common.error.ResourceNotFoundException;

/** 존재하지 않는 장바구니를 조회/변경하려 할 때. GlobalExceptionHandler가 404로 매핑한다. */
public class CartNotFoundException extends ResourceNotFoundException {

    public CartNotFoundException(long cartId) {
        super("cart not found: " + cartId);
    }
}
