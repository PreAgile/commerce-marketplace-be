package com.lemong.marketplace.cart.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Cart 도메인 property 테스트 — 임의의 담기 시퀀스에서도 돈 항등식과 오퍼 누적/분리 규칙이 항상 성립함을 잠근다. 예제
 * 테스트(CartTest)가 고정 입력 몇 개만 보는 것을 보완해 조합 경계를 난수로 폭넓게 친다.
 */
class CartPropertyTest {

	// 같은 (product,seller)는 항상 같은 단가 → 재담기 스냅샷 정책과 무관하게 total = Σ(price×qty)가 성립한다.
	private static long priceOf(long productId, long sellerId) {
		return productId * 1_000 + sellerId;
	}

	@Property(tries = 300)
	@Label("어떤 담기 시퀀스에서도 total == Σ(단가×수량)이고, 라인 수 == 서로 다른 (상품,셀러) 오퍼 수다")
	void totalIsSumOfLinesAndOffersMergeByKey(@ForAll("addSequences") List<int[]> adds) {
		Cart cart = Cart.createFor(1L);
		long expectedTotal = 0;
		for (int[] a : adds) {
			long productId = a[0];
			long sellerId = a[1];
			int qty = a[2];
			long price = priceOf(productId, sellerId);
			cart.addItem(productId, sellerId, price, qty);
			expectedTotal += price * (long) qty;
		}

		assertThat(cart.total().minor()).isEqualTo(expectedTotal);

		long distinctOffers = adds.stream().map(a -> a[0] + ":" + a[1]).distinct().count();
		assertThat(cart.getItems()).hasSize((int) distinctOffers);
	}

	@Provide
	Arbitrary<List<int[]>> addSequences() {
		Arbitrary<int[]> add = Combinators.combine(Arbitraries.integers().between(1, 3), // productId — 작은 범위라 병합/분리가 둘
																							// 다 발생
				Arbitraries.integers().between(1, 2), // sellerId
				Arbitraries.integers().between(1, 1_000)) // quantity
				.as((p, s, q) -> new int[]{p, s, q});
		return add.list().ofMinSize(1).ofMaxSize(40);
	}
}
