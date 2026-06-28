package com.lemong.marketplace.cart.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lemong.marketplace.common.error.AmountOverflowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cart 애그리거트 도메인 단위 테스트(순수 — DB·Spring 없음). 불변식·담기 규칙을 빠르게 검증한다. DB는 같은 불변식의 최후
 * 보루이며(CartConstraintsIT), 여기서는 도메인의 1차 방어를 본다.
 */
class CartTest {

	@Test
	@DisplayName("상품을 담으면 항목이 추가된다")
	void addItemCreatesLine() {
		Cart cart = Cart.createFor(1L);
		cart.addItem(100L, 10L, 3_000L, 2);

		assertThat(cart.getItems()).hasSize(1);
		assertThat(cart.total().minor()).isEqualTo(6_000L);
	}

	@Test
	@DisplayName("같은 상품을 또 담으면 수량이 누적된다(upsert)")
	void addingSameProductAccumulatesQuantity() {
		Cart cart = Cart.createFor(1L);
		cart.addItem(100L, 10L, 3_000L, 2);
		cart.addItem(100L, 10L, 3_000L, 3);

		assertThat(cart.getItems()).hasSize(1);
		assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(5);
		assertThat(cart.total().minor()).isEqualTo(15_000L);
	}

	@Test
	@DisplayName("서로 다른 셀러의 상품은 각각의 항목으로 공존한다(멀티셀러)")
	void differentSellersCoexist() {
		Cart cart = Cart.createFor(1L);
		cart.addItem(100L, 10L, 3_000L, 2); // 셀러 A
		cart.addItem(200L, 20L, 4_000L, 1); // 셀러 B

		assertThat(cart.getItems()).hasSize(2);
		assertThat(cart.getItems()).extracting(CartItem::getSellerId).containsExactlyInAnyOrder(10L, 20L);
		assertThat(cart.total().minor()).isEqualTo(10_000L); // 6000 + 4000
	}

	@Test
	@DisplayName("같은 상품이라도 셀러가 다르면 별도 라인이 된다(멀티셀러 오퍼 분리)")
	void sameProductDifferentSellerSeparateLines() {
		Cart cart = Cart.createFor(1L);
		cart.addItem(100L, 10L, 3_000L, 1); // 상품 100, 셀러 A
		cart.addItem(100L, 20L, 3_500L, 1); // 같은 상품 100, 셀러 B (다른 오퍼)

		assertThat(cart.getItems()).hasSize(2);
		assertThat(cart.getItems()).extracting(CartItem::getSellerId).containsExactlyInAnyOrder(10L, 20L);
		assertThat(cart.total().minor()).isEqualTo(6_500L); // 3000 + 3500, 병합되지 않음
	}

	@Test
	@DisplayName("0/음수 buyerId·productId·sellerId는 거부된다")
	void nonPositiveIdsRejected() {
		assertThatThrownBy(() -> Cart.createFor(0L)).isInstanceOf(IllegalArgumentException.class);
		Cart cart = Cart.createFor(1L);
		assertThatThrownBy(() -> cart.addItem(-1L, 10L, 3_000L, 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> cart.addItem(100L, 0L, 3_000L, 1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("수량 0 이하로 담으면 거부된다")
	void nonPositiveQuantityRejected() {
		Cart cart = Cart.createFor(1L);
		assertThatThrownBy(() -> cart.addItem(100L, 10L, 3_000L, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("음수 단가로 담으면 거부된다")
	void negativeUnitPriceRejected() {
		Cart cart = Cart.createFor(1L);
		assertThatThrownBy(() -> cart.addItem(100L, 10L, -1L, 1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("이미 담긴 오퍼라도 음수 단가로 다시 담으면 거부된다(검증이 카트 상태에 의존하지 않음)")
	void reAddWithInvalidPriceRejected() {
		Cart cart = Cart.createFor(1L);
		cart.addItem(100L, 10L, 3_000L, 1); // 첫 담기 성공 → 누적 분기로 진입하게 됨
		assertThatThrownBy(() -> cart.addItem(100L, 10L, -1L, 1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("수량 누적이 int 범위를 넘으면 조용히 wrap되지 않고 fail-loud로 터진다")
	void quantityAccumulationOverflowThrows() {
		Cart cart = Cart.createFor(1L);
		cart.addItem(100L, 10L, 1L, Integer.MAX_VALUE);
		assertThatThrownBy(() -> cart.addItem(100L, 10L, 1L, 1)).isInstanceOf(AmountOverflowException.class);
	}
}
