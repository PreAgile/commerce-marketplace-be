package com.lemong.marketplace.payment.application;

import com.lemong.marketplace.payment.domain.Payment;

public record PaymentView(Long paymentId, Long orderId, String status, long paidAmount) {
	public static PaymentView from(Payment payment) {
		return new PaymentView(payment.getId(), payment.getOrderId(), payment.getStatus().name(),
				payment.getPaidAmount());
	}
}
