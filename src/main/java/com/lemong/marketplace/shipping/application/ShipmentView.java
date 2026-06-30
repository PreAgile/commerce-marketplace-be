package com.lemong.marketplace.shipping.application;

import com.lemong.marketplace.shipping.domain.ShipmentStatus;
import java.time.OffsetDateTime;
import java.util.List;

/** 배송 조회 읽기 모델 — 현재 상태 + append-only 전이 이력. */
public record ShipmentView(long id, long orderId, long sellerId, ShipmentStatus status, List<Transition> history) {

	/** from은 최초 전이에서 null. */
	public record Transition(ShipmentStatus from, ShipmentStatus to, OffsetDateTime occurredAt) {
	}
}
