package com.lemong.marketplace.cart.domain;

/** 장바구니 상태. ACTIVE(담는 중) → ORDERED(주문으로 전환됨, 이후 변경 불가). */
public enum CartStatus {
    ACTIVE,
    ORDERED
}
