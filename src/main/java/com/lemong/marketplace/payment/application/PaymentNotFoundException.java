package com.lemong.marketplace.payment.application;

import com.lemong.marketplace.common.error.ResourceNotFoundException;

public class PaymentNotFoundException extends ResourceNotFoundException {
	public PaymentNotFoundException(long paymentId) {
		super("payment not found: " + paymentId);
	}
}
