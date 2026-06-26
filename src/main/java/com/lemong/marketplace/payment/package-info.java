/**
 * 결제(Payment) Bounded Context.
 *
 * <p>멱등키 UNIQUE로 따닥/중복 결제를 막고, 외부 PG에 멱등키를 end-to-end로 전달한다.
 * 결제 확정과 outbox INSERT는 같은 트랜잭션(dual-write 회피). 경계 규칙은 AGENTS.md 참조.
 */
package com.lemong.marketplace.payment;
