package com.lemong.marketplace.order.domain;

/**
 * 주문 라인 생성 입력. 주문 컨텍스트가 외부(장바구니 스냅샷)에서 받은 값을 도메인 타입으로 받기 위한 경계 DTO — 도메인이 cart
 * 타입에 의존하지 않도록 애플리케이션 계층이 이 타입으로 변환해 넘긴다.
 */
public record OrderLineSpec(long productId, long sellerId, long unitPrice, int quantity) {
}
