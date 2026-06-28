package com.lemong.marketplace.cart.published;

/**
 * 주문 컨텍스트가 장바구니를 소비(읽고 닫기)하기 위한 published 포트(cart가 제공, order가 소비 — 단방향).
 *
 * <p>
 * 읽기와 닫기를 한 번의 락 로드로 묶는다(@Version + FORCE_INCREMENT). 그래서 ① 스냅샷과 닫힌 카트가 같은 상태라
 * stale 주문이 불가능하고(읽기→닫기 사이 수정이 끼면 version 충돌로 거부), ② 같은 카트 동시 주문은 한쪽만 성공하며(이중
 * 주문 방지), ③ 이미 ORDERED면 주문을 만들기 전에 즉시 거부한다(fail-fast).
 */
public interface CartForOrder {

	CartSnapshot consumeForOrder(long cartId);
}
