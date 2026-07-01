package com.lemong.marketplace.shipping.web;

import com.lemong.marketplace.shipping.domain.ShipmentStatus;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public final class ShipmentRequests {

	private ShipmentRequests() {
	}

	// occurredAt은 택배사 사건 시각 — 생략하면 서버가 현재로 본다.
	public record RecordEvent(@NotNull ShipmentStatus status, OffsetDateTime occurredAt) {
	}
}
