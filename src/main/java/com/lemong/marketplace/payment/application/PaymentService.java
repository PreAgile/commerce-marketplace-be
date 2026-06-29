package com.lemong.marketplace.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lemong.marketplace.common.outbox.OutboxAppender;
import com.lemong.marketplace.payment.application.PaymentGateway.PgApproval;
import com.lemong.marketplace.payment.domain.Payment;
import com.lemong.marketplace.payment.infra.PaymentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
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

	// webmvc 스타터는 일반 ObjectMapper 빈을 노출하지 않는다 — outbox payload 직렬화 전용으로 직접
	// 보유(thread-safe, 재사용 안전).
	private static final ObjectMapper JSON = new ObjectMapper();

	private final PaymentRepository payments;
	private final PaymentGateway gateway;
	private final OutboxAppender outbox;
	private final JdbcClient jdbc;

	public PaymentService(PaymentRepository payments, PaymentGateway gateway, OutboxAppender outbox, JdbcClient jdbc) {
		this.payments = payments;
		this.gateway = gateway;
		this.outbox = outbox;
		this.jdbc = jdbc;
	}

	/**
	 * 멱등키로 선점. 같은 키면 항상 같은 결제 id로 수렴한다(동시 따닥 포함).
	 *
	 * <p>
	 * INSERT를 JPA save가 아닌 ON CONFLICT DO NOTHING으로 하는 이유: 동시 선점에서 JPA save는 23505를
	 * 던지고, 그 예외가 Hibernate 세션을 오염시켜(null identifier) catch 후 재조회마저
	 * AssertionFailure로 깨진다. ON CONFLICT는 충돌을 예외가 아닌 no-op으로 흡수하므로 세션 오염 자체가 없다 —
	 * 누가 넣었든 키로 재조회해 같은 id를 돌려준다.
	 */
	public long initiate(long orderId, String idempotencyKey, long amount) {
		Payment.initiate(orderId, idempotencyKey, amount); // 도메인 가드(amount>0 등)만 영속화 전에 검증
		jdbc.sql("""
				INSERT INTO payment (order_id, idempotency_key, paid_amount, status)
				VALUES (:orderId, :key, :amount, 'PENDING')
				ON CONFLICT (idempotency_key) DO NOTHING
				""").param("orderId", orderId).param("key", idempotencyKey).param("amount", amount).update();
		return jdbc.sql("SELECT id FROM payment WHERE idempotency_key = :key").param("key", idempotencyKey)
				.query(Long.class).single();
	}

	public PaymentView confirm(long paymentId) {
		Payment payment = payments.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
		if (payment.isPaid()) {
			return PaymentView.from(payment); // 멱등 no-op — 재시도는 PG를 다시 부르지 않는다
		}
		PgApproval approval = gateway.confirm(payment.getIdempotencyKey(), payment.getPaidAmount());
		payment.markPaid();
		outbox.append("payment", payment.getId(), "PaymentConfirmed", toPayload(payment, approval));
		return PaymentView.from(payment);
	}

	@Transactional(readOnly = true)
	public PaymentView getPayment(long paymentId) {
		return PaymentView
				.from(payments.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId)));
	}

	// pgTransactionId는 외부 PG가 준 문자열이라 수동 문자열 포매팅 시 따옴표·역슬래시가 JSON을 깨뜨릴 수 있다 →
	// Jackson에 위임.
	private String toPayload(Payment payment, PgApproval approval) {
		try {
			return JSON.writeValueAsString(new PaymentConfirmedEvent(payment.getId(), payment.getOrderId(),
					payment.getPaidAmount(), approval.pgTransactionId()));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("PaymentConfirmed payload 직렬화 실패: payment=" + payment.getId(), e);
		}
	}

	private record PaymentConfirmedEvent(long paymentId, long orderId, long amount, String pgTransactionId) {
	}
}
