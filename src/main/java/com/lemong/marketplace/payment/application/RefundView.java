package com.lemong.marketplace.payment.application;

/**
 * 환불 조회 응답. 누적환불·환불가능액은 저장 컬럼이 아니라 refund 원장 SUM으로 도출한다(잔액 컬럼 없음).
 *
 * @param refundedMinor
 *            누적환불 = SUM(refund.amount_minor)
 * @param refundableMinor
 *            남은 환불가능액 = paid_amount − 누적환불
 */
public record RefundView(long paymentId, long paidMinor, long refundedMinor, long refundableMinor) {
}
