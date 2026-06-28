package com.lemong.marketplace.order.domain;

/** 주문 상태. CREATED(생성됨) → PAID(결제 확정) → 또는 CANCELLED. 결제·취소 전이는 후속 슬라이스(S2+). */
public enum OrderStatus {
	CREATED, PAID, CANCELLED
}
