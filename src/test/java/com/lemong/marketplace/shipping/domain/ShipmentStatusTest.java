package com.lemong.marketplace.shipping.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 배송 상태머신의 전이 화이트리스트가 구조로 박제됨을 전수 검증한다. 합법성은 DB가 아니라 이 enum이 정의한다(V4 DDL 주석 —
 * "전이의 합법성·자가전이 금지는 앱 화이트리스트의 몫").
 */
class ShipmentStatusTest {

	// 테스트가 곧 명세 — 허용 전이를 여기 한 번 적고, 코드와 전수 대조한다.
	private static final Map<ShipmentStatus, Set<ShipmentStatus>> EXPECTED = Map.of(ShipmentStatus.READY,
			EnumSet.of(ShipmentStatus.PICKED_UP, ShipmentStatus.FAILED), ShipmentStatus.PICKED_UP,
			EnumSet.of(ShipmentStatus.IN_TRANSIT, ShipmentStatus.FAILED), ShipmentStatus.IN_TRANSIT,
			EnumSet.of(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.FAILED), ShipmentStatus.OUT_FOR_DELIVERY,
			EnumSet.of(ShipmentStatus.DELIVERED, ShipmentStatus.FAILED), ShipmentStatus.DELIVERED,
			EnumSet.noneOf(ShipmentStatus.class), ShipmentStatus.FAILED, EnumSet.noneOf(ShipmentStatus.class),
			ShipmentStatus.RETURNED, EnumSet.noneOf(ShipmentStatus.class));

	@Test
	@DisplayName("모든 (from,to) 쌍이 화이트리스트와 정확히 일치한다")
	void exhaustivePairsMatchWhitelist() {
		for (ShipmentStatus from : ShipmentStatus.values()) {
			for (ShipmentStatus to : ShipmentStatus.values()) {
				boolean expected = EXPECTED.get(from).contains(to);
				assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(expected);
			}
		}
	}

	@Test
	@DisplayName("자가 전이는 모든 상태에서 금지된다")
	void selfTransitionAlwaysRejected() {
		for (ShipmentStatus s : ShipmentStatus.values()) {
			assertThat(s.canTransitionTo(s)).as("%s -> %s", s, s).isFalse();
		}
	}

	@Test
	@DisplayName("DELIVERED·FAILED·RETURNED는 종료 상태라 어떤 전이도 못 한다")
	void terminalStatesHaveNoOutgoingTransition() {
		for (ShipmentStatus terminal : EnumSet.of(ShipmentStatus.DELIVERED, ShipmentStatus.FAILED,
				ShipmentStatus.RETURNED)) {
			assertThat(terminal.isTerminal()).as("%s terminal", terminal).isTrue();
			for (ShipmentStatus to : ShipmentStatus.values()) {
				assertThat(terminal.canTransitionTo(to)).as("%s -> %s", terminal, to).isFalse();
			}
		}
	}

	@Test
	@DisplayName("역전이(되돌리기)는 금지된다 — 예: PICKED_UP -> READY")
	void backwardTransitionRejected() {
		assertThat(ShipmentStatus.PICKED_UP.canTransitionTo(ShipmentStatus.READY)).isFalse();
		assertThat(ShipmentStatus.OUT_FOR_DELIVERY.canTransitionTo(ShipmentStatus.IN_TRANSIT)).isFalse();
	}

	@Test
	@DisplayName("단계 건너뛰기는 금지된다 — 예: READY -> DELIVERED")
	void skippingStagesRejected() {
		assertThat(ShipmentStatus.READY.canTransitionTo(ShipmentStatus.DELIVERED)).isFalse();
		assertThat(ShipmentStatus.READY.canTransitionTo(ShipmentStatus.IN_TRANSIT)).isFalse();
	}
}
