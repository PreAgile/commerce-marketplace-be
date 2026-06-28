package com.lemong.marketplace.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/**
 * Money 산술의 오버플로 안전성. long은 약 9.2×10^18에서 넘치는데, 넘으면 자바는 조용히 음수로 래핑한다 — 돈에선 잔액
 * 증발/생성으로 정산·대사가 깨지는 재앙이다. 따라서 plus/minus/times는 넘칠 때 조용히 래핑하지 않고
 * ArithmeticException으로 fail-fast 해야 한다(Math.*Exact).
 *
 * <p>
 * property: BigInteger를 오라클로 써 "진짜 결과가 long 범위면 값을 정확히 보존, 범위를 넘으면 예외"를 랜덤 입력
 * 수백 건으로 폭격한다(AGENTS.md — 돈 항등식은 예제 + property 함께). 순수 값 객체라 DB·Spring 불필요.
 */
class MoneyOverflowPropertyTest {

	// ----- 예제: 경계에서의 동작을 사람이 읽게 -----

	@Example
	void normalArithmeticIsUnaffected() {
		assertThat(Money.of(3_000).plus(Money.of(2_000)).minor()).isEqualTo(5_000);
		assertThat(Money.of(3_000).times(3).minor()).isEqualTo(9_000);
		assertThat(Money.ZERO.plus(Money.of(7)).minor()).isEqualTo(7);
	}

	@Example
	void plusOverflowFailsFast() {
		assertThatThrownBy(() -> Money.of(Long.MAX_VALUE).plus(Money.of(1))).isInstanceOf(ArithmeticException.class);
	}

	@Example
	void timesOverflowFailsFast() {
		assertThatThrownBy(() -> Money.of(Long.MAX_VALUE).times(2)).isInstanceOf(ArithmeticException.class);
	}

	// ----- property: BigInteger 오라클과 대조 -----

	@Property(tries = 500)
	void plusPreservesValueWithinRangeOrOverflows(@ForAll long a, @ForAll long b) {
		BigInteger exact = BigInteger.valueOf(a).add(BigInteger.valueOf(b));
		if (fitsInLong(exact)) {
			assertThat(Money.of(a).plus(Money.of(b)).minor()).isEqualTo(exact.longValueExact());
		} else {
			assertThatThrownBy(() -> Money.of(a).plus(Money.of(b))).isInstanceOf(ArithmeticException.class);
		}
	}

	@Property(tries = 500)
	void timesPreservesValueWithinRangeOrOverflows(@ForAll long base, @ForAll int quantity) {
		BigInteger exact = BigInteger.valueOf(base).multiply(BigInteger.valueOf(quantity));
		if (fitsInLong(exact)) {
			assertThat(Money.of(base).times(quantity).minor()).isEqualTo(exact.longValueExact());
		} else {
			assertThatThrownBy(() -> Money.of(base).times(quantity)).isInstanceOf(ArithmeticException.class);
		}
	}

	private static boolean fitsInLong(BigInteger v) {
		return v.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
				&& v.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0;
	}
}
