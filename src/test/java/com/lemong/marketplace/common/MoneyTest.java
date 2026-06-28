package com.lemong.marketplace.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Money VO 단위 테스트 — 특히 오버플로우가 *조용히 래핑되지 않고* 큰 소리로 실패함을 박는다. */
class MoneyTest {

	@Test
	@DisplayName("정상 합·곱은 그대로 계산된다")
	void normalArithmetic() {
		assertThat(Money.of(3_000).times(2).minor()).isEqualTo(6_000L);
		assertThat(Money.of(6_000).plus(Money.of(4_000)).minor()).isEqualTo(10_000L);
	}

	@Test
	@DisplayName("곱셈 오버플로우는 음수로 래핑되지 않고 ArithmeticException으로 터진다")
	void multiplyOverflowThrows() {
		Money huge = Money.of(Long.MAX_VALUE / 2 + 1);
		assertThatThrownBy(() -> huge.times(2)).isInstanceOf(ArithmeticException.class);
	}

	@Test
	@DisplayName("덧셈 오버플로우도 ArithmeticException으로 터진다")
	void addOverflowThrows() {
		Money huge = Money.of(Long.MAX_VALUE);
		assertThatThrownBy(() -> huge.plus(Money.of(1))).isInstanceOf(ArithmeticException.class);
	}
}
