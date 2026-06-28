package com.lemong.marketplace.payment.application;

/**
 * 외부 PG로의 아웃바운드 포트(외부 경계 — 테스트에선 Fake, 실서비스에선 실 PG 연동으로 대체). 멱등키를 그대로 전달해 PG 측
 * 멱등을 보장한다(따닥/재시도에 PG가 중복 청구하지 않도록 end-to-end).
 */
public interface PaymentGateway {

	PgApproval confirm(String idempotencyKey, long amount);

	record PgApproval(String pgTransactionId, long approvedAmount) {
	}
}
