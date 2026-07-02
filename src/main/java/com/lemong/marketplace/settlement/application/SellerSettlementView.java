package com.lemong.marketplace.settlement.application;

import com.lemong.marketplace.settlement.domain.CycleType;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 셀러 정산 조회 읽기 모델(GET /sellers/{id}/settlements). 마감된 사이클별 순지급과 아직 마감되지 않은 미배정
 * 순액(pending)을 한 화면에 담는다. 모든 금액은 부호 원장 SUM 도출이라 잔액 컬럼이 없다.
 *
 * @param totalNetMinor
 *            마감분 + pending 합 = 이 셀러의 전체 원장 순액
 */
public record SellerSettlementView(long sellerId, List<CycleNet> cycles, long pendingNetMinor, long totalNetMinor) {

	public record CycleNet(long cycleId, CycleType cycleType, OffsetDateTime periodStart, OffsetDateTime periodEnd,
			String status, int lineCount, long netMinor) {
	}
}
