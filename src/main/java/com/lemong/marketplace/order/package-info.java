/**
 * 주문(Order) Bounded Context.
 *
 * <p>
 * 다른 컨텍스트(payment·shipping·settlement)의 내부 엔티티·테이블을 직접 참조하지 않는다. 컨텍스트 간 통신은
 * 이벤트(outbox) 또는 id 참조만. 경계 규칙은 AGENTS.md 참조.
 */
package com.lemong.marketplace.order;
