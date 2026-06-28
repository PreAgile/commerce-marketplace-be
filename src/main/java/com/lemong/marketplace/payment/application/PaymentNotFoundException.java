package com.lemong.marketplace.payment.application;

import com.lemong.marketplace.common.error.ResourceNotFoundException;

/** 존재하지 않는 결제를 조회/확정하려 할 때. GlobalExceptionHandler가 404로 매핑한다. */
public class PaymentNotFoundException extends ResourceNotFoundException {

	public PaymentNotFoundException(long paymentId) {
		super("payment not found: " + paymentId);
	}
}
