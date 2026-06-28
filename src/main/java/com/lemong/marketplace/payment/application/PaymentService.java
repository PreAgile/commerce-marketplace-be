package com.lemong.marketplace.payment.application;

import com.lemong.marketplace.common.outbox.OutboxAppender;
import com.lemong.marketplace.payment.application.PaymentGateway.PgApproval;
import com.lemong.marketplace.payment.domain.Payment;
import com.lemong.marketplace.payment.infra.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 승인·확정 — 골든 시나리오 S2. 선점(PENDING)과 PG 확정(PAID + outbox)을 멱등하게 처리한다.
 *
 * <p>
 * confirm은 PG 확정과 outbox 적재를 <b>한 트랜잭션</b>으로 묶는다(dual-write를 원자화, ADR-002). 멱등은
 * 3겹: ① 이미 PAID면 no-op ② PG에 멱등키 end-to-end 전달(중복 청구 방지) ③ outbox uq가 중복 적재 차단.
 */
@Service
@Transactional
public class PaymentService {

	private final PaymentRepository payments;
	private final PaymentGateway gateway;
	private final OutboxAppender outbox;

	public PaymentService(PaymentRepository payments, PaymentGateway gateway, OutboxAppender outbox) {
		this.payments = payments;
		this.gateway = gateway;
		this.outbox = outbox;
	}

	/** 멱등키로 선점. 같은 키면 기존 결제를 그대로 반환한다(동시 선점은 uq 충돌을 재조회로 흡수). */
	public long initiate(long orderId, String idempotencyKey, long amount) {
		return payments.findByIdempotencyKey(idempotencyKey).map(Payment::getId).orElseGet(() -> {
			try {
				return payments.save(Payment.initiate(orderId, idempotencyKey, amount)).getId();
			} catch (DataIntegrityViolationException concurrentDuplicate) {
				return payments.findByIdempotencyKey(idempotencyKey).orElseThrow().getId();
			}
		});
	}

	public PaymentView confirm(long paymentId) {
		Payment payment = payments.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
		if (payment.isPaid()) {
			return PaymentView.from(payment); // 멱등 no-op — 재시도는 PG를 다시 부르지 않는다
		}
		PgApproval approval = gateway.confirm(payment.getIdempotencyKey(), payment.getPaidAmount());
		payment.markPaid();
		outbox.append("payment", payment.getId(), "PaymentConfirmed",
				"{\"paymentId\":%d,\"orderId\":%d,\"amount\":%d,\"pgTransactionId\":\"%s\"}".formatted(payment.getId(),
						payment.getOrderId(), payment.getPaidAmount(), approval.pgTransactionId()));
		return PaymentView.from(payment);
	}

	@Transactional(readOnly = true)
	public PaymentView getPayment(long paymentId) {
		return PaymentView
				.from(payments.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId)));
	}
}
