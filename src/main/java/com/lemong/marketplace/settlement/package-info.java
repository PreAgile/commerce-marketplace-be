/**
 * 정산(Settlement) Bounded Context.
 *
 * <p>
 * 원장은 부호 있는 append-only(잔액 컬럼 직접 UPDATE 금지, 잔액은 SUM 도출). 총액 보존· "0 ≤ 누적환불 ≤ 결제"
 * 불변식을 DB 제약으로 강제하고, 지급은 payout 게이트(net≥0 AND CONFIRMED)로 차단한다. PG 입금 4-way 대사로
 * 정합을 상시 검증한다. 경계 규칙은 AGENTS.md 참조.
 */
package com.lemong.marketplace.settlement;
