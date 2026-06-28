package com.lemong.marketplace.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderTest {

	private static OrderLineSpec line(long product, long seller, long price, int qty) {
		return new OrderLineSpec(product, seller, price, qty);
	}

	@Test
	@DisplayName("총액은 라인 금액의 합이다(멀티셀러)")
	void totalIsSumOfLines() {
		Order order = Order.place(1L, List.of(line(100, 10, 3_000, 2), line(200, 20, 4_000, 1)));

		assertThat(order.getLines()).hasSize(2);
		assertThat(order.total().minor()).isEqualTo(10_000);
	}

	@Test
	@DisplayName("서로 다른 셀러 라인이 한 주문에 공존한다")
	void multiSellerLinesCoexist() {
		Order order = Order.place(1L, List.of(line(100, 10, 1_000, 1), line(100, 20, 1_000, 1)));

		assertThat(order.getLines()).extracting(OrderLine::getSellerId).containsExactlyInAnyOrder(10L, 20L);
	}

	@Test
	@DisplayName("라인이 없는 주문은 거부된다")
	void emptyOrderRejected() {
		assertThatThrownBy(() -> Order.place(1L, List.of())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("buyerId가 양수가 아니면 거부된다")
	void nonPositiveBuyerRejected() {
		assertThatThrownBy(() -> Order.place(0L, List.of(line(100, 10, 1_000, 1))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("수량이 0 이하인 라인은 거부된다")
	void nonPositiveQuantityRejected() {
		assertThatThrownBy(() -> Order.place(1L, List.of(line(100, 10, 1_000, 0))))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
