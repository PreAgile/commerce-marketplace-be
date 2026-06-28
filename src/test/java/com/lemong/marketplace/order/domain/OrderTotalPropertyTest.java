package com.lemong.marketplace.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 주문 보존 불변식을 property로 검증한다: {@code Order.place}의 총액은 항상 Σ(라인 단가×수량)이다.
 *
 * <p>
 * 예제(OrderTest)는 한 경우만 본다. 라인 수·단가·수량·셀러의 임의 조합을 수백 건 폭격해, 멀티셀러·중복 라인에서도 합이
 * 보존됨을 확인한다(AGENTS.md — 돈 항등식은 예제 + property 함께). 도메인 레벨이라 DB·Spring 불필요. 생성
 * 범위는 long 오버플로가 안 나도록 제한한다(오버플로 자체는 Money/MoneyOverflowPropertyTest가 따로 검증).
 */
class OrderTotalPropertyTest {

	@Property(tries = 500)
	void totalEqualsSumOfLineAmounts(@ForAll("lineSpecs") List<OrderLineSpec> specs) {
		long expected = specs.stream().mapToLong(s -> s.unitPrice() * s.quantity()).sum();

		assertThat(Order.place(1L, specs).total().minor()).isEqualTo(expected);
	}

	@Provide
	Arbitrary<List<OrderLineSpec>> lineSpecs() {
		Arbitrary<Long> productId = Arbitraries.longs().between(1, 10_000);
		Arbitrary<Long> sellerId = Arbitraries.longs().between(1, 100);
		Arbitrary<Long> unitPrice = Arbitraries.longs().between(0, 1_000_000);
		Arbitrary<Integer> quantity = Arbitraries.integers().between(1, 1_000);
		Arbitrary<OrderLineSpec> line = Combinators.combine(productId, sellerId, unitPrice, quantity)
				.as(OrderLineSpec::new);
		return line.list().ofMinSize(1).ofMaxSize(10); // 최대 10줄×10^6×10^3 = 10^10 « long, 오버플로 없음
	}
}
