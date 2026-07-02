package com.lemong.marketplace.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lemong.marketplace.common.outbox.OutboxAppender;
import com.lemong.marketplace.payment.application.PaymentGateway.PgRefund;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 부분환불 — 골든 시나리오 S6 (M5-a, payment BC). 환불을 append-only refund 원장에 쌓고, 누적환불은
 * {@code SUM(refund.amount_minor)}로 도출한다(잔액 컬럼 없음, ADR-019). 소비(정산 REFUND 음수
 * 라인)는 M5-b.
 *
 * <p>
 * 불변식 {@code 0 ≤ 누적환불 ≤ 결제금액}은 코드 if가 아니라 두 층으로 강제한다:
 * <ul>
 * <li>payment 행 {@code FOR UPDATE}로 같은 결제의 동시 환불을 직렬화(ADR-005 핫로우). 잠금을 쥔 뒤
 * 상태·금액을 읽어야 앞 환불의 커밋분을 본다.
 * <li>DB {@code trg_refund_within_paid} 트리거가 SUM(환불) ≤ paid_amount를 INSERT 시점에
 * 강제(ADR-008 교차행 트리거 계열, 최후 보루). 하한(&gt;0)은 CHECK.
 * </ul>
 *
 * <p>
 * 멱등은 confirm의 isPaid no-op과 같은 결로, 잠금 안에서 같은 토큰 환불이 이미 있으면 PG를 다시 부르지 않고
 * no-op으로 흘린다. {@code uq_refund_event}가 최후 보루.
 */
@Service
@Transactional
public class RefundService {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final PaymentGateway gateway;
	private final OutboxAppender outbox;
	private final JdbcClient jdbc;

	public RefundService(PaymentGateway gateway, OutboxAppender outbox, JdbcClient jdbc) {
		this.gateway = gateway;
		this.outbox = outbox;
		this.jdbc = jdbc;
	}

	/**
	 * 주문의 결제에서 {@code orderLineId} 라인 몫 {@code amount}를 환불한다. 결제는 order_id로 찾는다(한
	 * 주문 = 한 결제, PlaceOrder→confirm 흐름).
	 *
	 * @throws PaymentNotFoundException
	 *             주문에 결제가 없을 때(404)
	 * @throws IllegalStateException
	 *             결제가 PAID가 아닐 때(409)
	 * @throws org.springframework.dao.DataIntegrityViolationException
	 *             누적환불이 결제금액을 초과할 때(트리거, 409)
	 */
	public RefundView refund(long orderId, long orderLineId, long amount, String idempotencyKey) {
		if (amount <= 0) {
			throw new IllegalArgumentException("refund amount must be > 0, but was " + amount);
		}

		// 핫로우 직렬화: 같은 결제의 동시 환불을 한 줄로 통과시킨다. 잠금을 쥔 뒤 상태·금액을 읽어야 READ COMMITTED에서
		// 직전 환불의 커밋분을 SUM에 반영해 본다(잠금 대기 = 앞 트랜잭션 커밋 완료).
		Locked payment = jdbc.sql("SELECT id, paid_amount, status FROM payment WHERE order_id = :oid FOR UPDATE")
				.param("oid", orderId)
				.query((rs, n) -> new Locked(rs.getLong("id"), rs.getLong("paid_amount"), rs.getString("status")))
				.optional().orElseThrow(() -> new PaymentNotFoundException(orderId));
		if (!"PAID".equals(payment.status())) {
			throw new IllegalStateException(
					"payment for order " + orderId + " is not PAID (status=" + payment.status() + "), cannot refund");
		}

		// 멱등: 이 결제에 같은 토큰 환불이 이미 있으면 PG 재호출·중복 이벤트 없이 no-op(confirm의 isPaid 선례).
		// payment_id로 스코프를 좁힌다 — 전역으로 걸면 다른 주문에 같은 토큰을 재사용했을 때 이 결제 상태를 잘못된
		// no-op으로 반환한다. 토큰 교차 오용은 여기서 안 걸리고 uq_refund_event(전역)가 INSERT에서 409로 막는다.
		boolean already = jdbc.sql("SELECT 1 FROM refund WHERE source_event_id = :evid AND payment_id = :pid")
				.param("evid", idempotencyKey).param("pid", payment.id()).query(Integer.class).optional().isPresent();
		if (!already) {
			PgRefund pg = gateway.refund(idempotencyKey, amount);
			long refundId = jdbc.sql("""
					INSERT INTO refund (payment_id, order_line_id, amount_minor, source_event_id)
					VALUES (:pid, :line, :amt, :evid)
					RETURNING id
					""").param("pid", payment.id()).param("line", orderLineId).param("amt", pg.refundedAmount())
					.param("evid", idempotencyKey).query(Long.class).single();
			// 이벤트의 aggregate는 결제가 아니라 이 환불이다 — 한 결제에 환불이 여러 번이라 aggregate_id=paymentId면
			// uq_outbox_event(aggregate_type,aggregate_id,event_type)가 둘째 환불을 막는다.
			// refundId로 분리.
			outbox.append("refund", refundId, "PaymentRefunded",
					toPayload(payment.id(), orderId, orderLineId, pg.refundedAmount(), refundId));
		}

		long refunded = jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM refund WHERE payment_id = :pid")
				.param("pid", payment.id()).query(Long.class).single();
		return new RefundView(payment.id(), payment.paidAmount(), refunded, payment.paidAmount() - refunded);
	}

	private String toPayload(long paymentId, long orderId, long orderLineId, long amount, long refundId) {
		try {
			return JSON.writeValueAsString(new PaymentRefundedEvent(paymentId, orderId, orderLineId, amount, refundId));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("PaymentRefunded payload 직렬화 실패: payment=" + paymentId, e);
		}
	}

	private record Locked(long id, long paidAmount, String status) {
	}

	// settlement(M5-b)가 orderLineId로 셀러를 귀속하고 refundId로 멱등키("refund:{refundId}")를
	// 만든다.
	private record PaymentRefundedEvent(long paymentId, long orderId, long orderLineId, long amountMinor,
			long refundId) {
	}
}
