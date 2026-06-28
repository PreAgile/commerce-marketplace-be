/**
 * 장바구니(Cart) 컨텍스트 — 주문 전 단계의 staging. 정산 핵심 BC가 아닌 보조 컨텍스트다.
 *
 * <p>
 * 다른 컨텍스트의 내부 엔티티를 직접 참조하지 않는다. 주문 전환은 주문 컨텍스트가 cart를 읽어서 하고,
 * product_id·seller_id는 외부 도메인의 논리 참조일 뿐이다. 경계 규칙은 AGENTS.md 참조.
 */
package com.lemong.marketplace.cart;
