package com.lemong.marketplace.payment.application;

public interface PaymentGateway {
	PgApproval confirm(String idempotencyKey, long amount);

	record PgApproval(String pgTransactionId, long approvedAmount) {
	}
}
