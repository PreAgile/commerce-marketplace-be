package com.lemong.marketplace.payment.web;

import com.lemong.marketplace.payment.application.PaymentService;
import com.lemong.marketplace.payment.application.PaymentView;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 결제 API. 골든 시나리오 S2(결제 승인·확정). */
@RestController
@RequestMapping("/payments")
public class PaymentController {

	private final PaymentService payments;

	public PaymentController(PaymentService payments) {
		this.payments = payments;
	}

	public record InitiatePaymentResponse(long paymentId) {
	}

	@PostMapping
	public ResponseEntity<InitiatePaymentResponse> initiate(@Valid @RequestBody PaymentRequests.InitiatePayment req) {
		long paymentId = payments.initiate(req.orderId(), req.idempotencyKey(), req.amount());
		return ResponseEntity.created(URI.create("/payments/" + paymentId))
				.body(new InitiatePaymentResponse(paymentId));
	}

	@PostMapping("/{id}/confirm")
	public PaymentView confirm(@PathVariable long id) {
		return payments.confirm(id);
	}

	@GetMapping("/{id}")
	public PaymentView getPayment(@PathVariable long id) {
		return payments.getPayment(id);
	}
}
