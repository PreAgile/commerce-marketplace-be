package com.lemong.marketplace.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.lemong.marketplace.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 주문 생성 인수 테스트 — 골든 시나리오 S1 후반(장바구니 → 주문). 카트를 읽어 멀티셀러 주문으로 전환하고 카트를
 * 닫는(ORDERED) 풀스택 흐름을 실 Postgres로 검증한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OrderAcceptanceIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE order_line, orders, cart_item, cart RESTART IDENTITY CASCADE").update();
	}

	private long givenCartWith(long buyerId, String... itemsJson) throws Exception {
		String body = mvc
				.perform(post("/carts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"buyerId\": " + buyerId + "}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long cartId = ((Number) JsonPath.parse(body).read("$.cartId")).longValue();
		for (String item : itemsJson) {
			mvc.perform(post("/carts/" + cartId + "/items").contentType(MediaType.APPLICATION_JSON).content(item))
					.andExpect(status().isOk());
		}
		return cartId;
	}

	private long placeOrder(long cartId) throws Exception {
		String body = mvc
				.perform(
						post("/orders").contentType(MediaType.APPLICATION_JSON).content("{\"cartId\": " + cartId + "}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.parse(body).read("$.orderId")).longValue();
	}

	@Nested
	@DisplayName("장바구니를 주문으로 전환할 때")
	class PlacingOrder {

		@Test
		@DisplayName("Given 두 셀러의 상품이 담긴 카트, When 주문하면, Then 라인 2개·총액이 보이고 카트는 ORDERED가 된다")
		void multiSellerCartBecomesOrder() throws Exception {
			long cartId = givenCartWith(1L, "{\"productId\":100,\"sellerId\":10,\"unitPrice\":3000,\"quantity\":2}", // 6000
					"{\"productId\":200,\"sellerId\":20,\"unitPrice\":4000,\"quantity\":1}"); // 4000

			long orderId = placeOrder(cartId);

			mvc.perform(get("/orders/" + orderId)).andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("CREATED")).andExpect(jsonPath("$.items.length()").value(2))
					.andExpect(jsonPath("$.totalAmount").value(10_000));

			mvc.perform(get("/carts/" + cartId)).andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("ORDERED"));
		}
	}

	@Nested
	@DisplayName("잘못된 주문 요청")
	class InvalidRequests {

		@Test
		@DisplayName("이미 주문된 카트를 또 주문하면 409")
		void orderingOrderedCartIsConflict() throws Exception {
			long cartId = givenCartWith(1L, "{\"productId\":100,\"sellerId\":10,\"unitPrice\":3000,\"quantity\":1}");
			placeOrder(cartId);

			mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{\"cartId\": " + cartId + "}"))
					.andExpect(status().isConflict());
		}

		@Test
		@DisplayName("빈 카트로 주문하면 400")
		void orderingEmptyCartIsBadRequest() throws Exception {
			long cartId = givenCartWith(1L);

			mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{\"cartId\": " + cartId + "}"))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("존재하지 않는 카트로 주문하면 404")
		void orderingMissingCartIsNotFound() throws Exception {
			mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{\"cartId\": 999999}"))
					.andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("존재하지 않는 주문 조회는 404")
		void getMissingOrderIsNotFound() throws Exception {
			mvc.perform(get("/orders/999999")).andExpect(status().isNotFound());
		}
	}
}
