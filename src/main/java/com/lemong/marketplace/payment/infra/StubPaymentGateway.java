package com.lemong.marketplace.payment.infra;

import com.lemong.marketplace.payment.application.PaymentGateway;
import org.springframework.stereotype.Component;

/**
 * 실 PG 연동 전의 자리표시자 구현. 멱등키 기반으로 결정적 승인을 반환하므로(같은 키 → 같은 거래 id) 재시도에도 PG가 중복
 * 청구하지 않는 end-to-end 멱등을 흉내낸다. 실 PG(토스 등) 연동 시 이 빈만 교체한다.
 */
@Component
class StubPaymentGateway implements PaymentGateway {
	@Override
	public PgApproval confirm(String idempotencyKey, long amount) {
		return new PgApproval("stub-" + idempotencyKey, amount);
	}
}
