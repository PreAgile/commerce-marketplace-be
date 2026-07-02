package com.lemong.marketplace.payment.web;

import com.lemong.marketplace.payment.application.RefundService;
import com.lemong.marketplace.payment.application.RefundView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부분환불 API — 골든 시나리오 S6. 경로는 고객 모델(주문)을 따르나 로직은 payment BC다: 결제를 order_id로 찾아
 * 자기 테이블(payment·refund)만 다룬다(order BC 미참조, 경계 유지). 한 주문 = 한 결제 전제.
 */
@RestController
public class RefundController {

	private final RefundService refunds;

	public RefundController(RefundService refunds) {
		this.refunds = refunds;
	}

	@PostMapping("/orders/{orderId}/refunds")
	public RefundView refund(@PathVariable long orderId, @Valid @RequestBody PaymentRequests.Refund req) {
		return refunds.refund(orderId, req.orderLineId(), req.amount(), req.idempotencyKey());
	}
}
