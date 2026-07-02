package com.lemong.marketplace.settlement.web;

import com.lemong.marketplace.settlement.domain.CycleType;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public final class SettlementRequests {

	private SettlementRequests() {
	}

	// 마감할 정산 창 [periodStart, periodEnd). 반열림 검증은 서비스가(닫힌 과거 창 계약).
	public record RunClose(@NotNull CycleType cycleType, @NotNull OffsetDateTime periodStart,
			@NotNull OffsetDateTime periodEnd) {
	}
}
