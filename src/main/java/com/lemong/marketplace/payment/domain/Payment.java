package com.lemong.marketplace.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 결제 애그리거트 루트. order_id는 주문 컨텍스트의 id 참조일 뿐 객체로 끌어오지 않는다(BC 경계).
 *
 * <p>
 * refunded_amount·created_at은 DB가 소유(전자는 M5 환불 슬라이스, 후자는 DEFAULT)라 매핑하지 않는다 —
 * ddl-auto=validate는 미매핑 컬럼을 문제삼지 않는다.
 */
@Entity
@Table(name = "payment")
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "order_id", nullable = false)
	private Long orderId;

	@Column(name = "idempotency_key", nullable = false)
	private String idempotencyKey;

	@Column(name = "paid_amount", nullable = false)
	private long paidAmount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentStatus status;

	protected Payment() {
		// JPA
	}

	private Payment(long orderId, String idempotencyKey, long amount) {
		this.orderId = orderId;
		this.idempotencyKey = idempotencyKey;
		this.paidAmount = amount;
		this.status = PaymentStatus.PENDING;
	}

	public static Payment initiate(long orderId, String idempotencyKey, long amount) {
		if (orderId <= 0) {
			throw new IllegalArgumentException("orderId must be > 0, but was " + orderId);
		}
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("idempotencyKey must not be blank");
		}
		if (amount <= 0) {
			throw new IllegalArgumentException("amount must be > 0, but was " + amount);
		}
		return new Payment(orderId, idempotencyKey, amount);
	}

	// PG 확정. PENDING에서만 PAID로 — 이미 PAID면 호출 전에 멱등 no-op로 거르고, CANCELLED→PAID는 막는다.
	public void markPaid() {
		if (status != PaymentStatus.PENDING) {
			throw new IllegalStateException("payment " + id + " cannot be paid from " + status);
		}
		this.status = PaymentStatus.PAID;
	}

	public boolean isPaid() {
		return status == PaymentStatus.PAID;
	}

	public Long getId() {
		return id;
	}

	public Long getOrderId() {
		return orderId;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public long getPaidAmount() {
		return paidAmount;
	}

	public PaymentStatus getStatus() {
		return status;
	}
}
