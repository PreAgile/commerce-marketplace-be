package com.lemong.marketplace.cart.published;

import java.util.List;

/**
 * 장바구니의 읽기 전용 published 모델 — 다른 컨텍스트(주문)가 cart 내부 엔티티를 보지 않고 내용을 읽기 위한 계약. 이
 * 패키지(published)만 BC 경계를 넘어 참조 가능하다(ArchitectureTest가 강제).
 */
public record CartSnapshot(long cartId, long buyerId, String status, List<Line> lines) {

	public record Line(long productId, long sellerId, long unitPrice, int quantity) {
	}
}
