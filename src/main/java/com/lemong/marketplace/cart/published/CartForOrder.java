package com.lemong.marketplace.cart.published;

/**
 * 주문 컨텍스트가 장바구니를 읽고 닫기 위한 published 포트(cart가 제공, order가 소비 — 단방향).
 *
 * <p>
 * {@link #markOrdered}는 cart의 낙관적 락(@Version + FORCE_INCREMENT)을 타므로, 같은 카트를
 * 동시에 주문하면 한쪽만 성공하고 나머지는 충돌한다(이중 주문 방지). 이미 ORDERED면 거부한다.
 */
public interface CartForOrder {

	CartSnapshot read(long cartId);

	void markOrdered(long cartId);
}
