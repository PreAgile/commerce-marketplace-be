package com.lemong.marketplace.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lemong.marketplace.settlement.application.SettlementService;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * 수수료 계산의 돈 항등식을 property로 폭격한다: 셀러 순지급(SALE + COMMISSION)이 어떤 (매출 × 수수료율)에서도
 * 음수로 떨어지지 않고, 수수료는 매출을 넘지 않는다. rate 상한 100%가 이 불변식을 떠받친다.
 */
class CommissionPropertyTest {

	@Property
	void commissionIsBoundedBySaleSoNetPayoutStaysNonNegative(
			@ForAll @LongRange(min = 1, max = 1_000_000_000_000L) long amount,
			@ForAll @IntRange(min = 0, max = 10_000) int rateBps) {
		long commission = SettlementService.commissionMinor(amount, rateBps);

		// 0 <= commission <= amount
		assertThat(commission).isBetween(0L, amount);
		// 순지급 = SALE(+amount) + COMMISSION(-commission) >= 0
		assertThat(amount - commission).isGreaterThanOrEqualTo(0L);
	}

	@Property
	void higherRateNeverYieldsLessCommission(@ForAll @LongRange(min = 1, max = 1_000_000_000_000L) long amount,
			@ForAll @IntRange(min = 0, max = 9_999) int rateBps) {
		// 단조성: 수수료율이 오르면 수수료도 줄지 않는다(floor 반올림에도).
		assertThat(SettlementService.commissionMinor(amount, rateBps + 1))
				.isGreaterThanOrEqualTo(SettlementService.commissionMinor(amount, rateBps));
	}
}
