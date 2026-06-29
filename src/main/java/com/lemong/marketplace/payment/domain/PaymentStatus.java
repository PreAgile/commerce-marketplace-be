package com.lemong.marketplace.payment.domain;

/**
 * 결제 상태. PENDING(선점) → PAID(PG 확정) 또는 CANCELLED. 역전이는 금지(markPaid는 PENDING에서만).
 */
public enum PaymentStatus {
	PENDING, PAID, CANCELLED
}
