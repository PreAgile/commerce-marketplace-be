package com.lemong.marketplace.cart;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 장바구니 인수 테스트 — 경량 BDD(Given/When/Then). API→서비스→도메인→실 DB 풀스택을 비즈니스 언어로 검증한다.
 * 골든 시나리오 S1의 "담기" 단계가 실제로 동작함을 사람이 읽을 수 있는 형태로 증명한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CartAcceptanceIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE cart_item, cart RESTART IDENTITY CASCADE").update();
	}

	/** Given: 구매자의 새 장바구니가 있다. */
	private long givenNewCart(long buyerId) throws Exception {
		String body = mvc
				.perform(post("/carts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"buyerId\": " + buyerId + "}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.parse(body).read("$.cartId")).longValue();
	}

	private void addItem(long cartId, long productId, long sellerId, long unitPrice, int quantity) throws Exception {
		mvc.perform(post("/carts/" + cartId + "/items").contentType(MediaType.APPLICATION_JSON).content("""
				{"productId": %d, "sellerId": %d, "unitPrice": %d, "quantity": %d}
				""".formatted(productId, sellerId, unitPrice, quantity))).andExpect(status().isOk());
	}

	@Nested
	@DisplayName("장바구니에 상품을 담을 때")
	class AddingItems {

		@Test
		@DisplayName("Given 새 장바구니, When 두 셀러의 상품을 담으면, Then 두 항목과 합계가 보인다")
		void multiSellerItemsAppear() throws Exception {
			long cartId = givenNewCart(1L);

			addItem(cartId, 100L, 10L, 3_000L, 2); // 셀러 A: 6000
			addItem(cartId, 200L, 20L, 4_000L, 1); // 셀러 B: 4000

			mvc.perform(get("/carts/" + cartId)).andExpect(status().isOk())
					.andExpect(jsonPath("$.items.length()").value(2))
					.andExpect(jsonPath("$.totalAmount").value(10_000));
		}

		@Test
		@DisplayName("Given 한 상품을 셀러 A로 담음, When 같은 상품을 셀러 B로 담으면, Then 오퍼가 합쳐지지 않고 2줄이 된다")
		void sameProductDifferentSellerStaysSeparate() throws Exception {
			long cartId = givenNewCart(1L);

			addItem(cartId, 100L, 10L, 3_000L, 1); // 상품 100, 셀러 A
			addItem(cartId, 100L, 20L, 3_500L, 1); // 같은 상품 100, 셀러 B

			mvc.perform(get("/carts/" + cartId)).andExpect(status().isOk())
					.andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.totalAmount").value(6_500)); // 병합되지
																															// 않음
		}

		@Test
		@DisplayName("Given 이미 담긴 상품, When 같은 상품을 또 담으면, Then 수량이 누적된다")
		void sameProductAccumulates() throws Exception {
			long cartId = givenNewCart(1L);

			addItem(cartId, 100L, 10L, 3_000L, 2);
			addItem(cartId, 100L, 10L, 3_000L, 3);

			mvc.perform(get("/carts/" + cartId)).andExpect(status().isOk())
					.andExpect(jsonPath("$.items.length()").value(1))
					.andExpect(jsonPath("$.items[0].quantity").value(5))
					.andExpect(jsonPath("$.totalAmount").value(15_000));
		}
	}

	@Nested
	@DisplayName("잘못된 요청")
	class InvalidRequests {

		@Test
		@DisplayName("수량 0으로 담으면 400 + ProblemDetail(status=400)")
		void zeroQuantityIsBadRequest() throws Exception {
			long cartId = givenNewCart(1L);
			mvc.perform(post("/carts/" + cartId + "/items").contentType(MediaType.APPLICATION_JSON)
					.content("{\"productId\":100,\"sellerId\":10,\"unitPrice\":3000,\"quantity\":0}"))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
					.andExpect(jsonPath("$.detail").exists());
		}

		@Test
		@DisplayName("존재하지 않는 장바구니에 담으면 404 + ProblemDetail(status=404, detail)")
		void addingToMissingCartIsNotFound() throws Exception {
			mvc.perform(post("/carts/999999/items").contentType(MediaType.APPLICATION_JSON)
					.content("{\"productId\":100,\"sellerId\":10,\"unitPrice\":3000,\"quantity\":1}"))
					.andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404))
					.andExpect(jsonPath("$.detail").value("cart not found: 999999"));
		}

		@Test
		@DisplayName("존재하지 않는 장바구니 조회는 404 + ProblemDetail(status=404, detail)")
		void getMissingCartIsNotFound() throws Exception {
			mvc.perform(get("/carts/999999")).andExpect(status().isNotFound())
					.andExpect(jsonPath("$.status").value(404))
					.andExpect(jsonPath("$.detail").value("cart not found: 999999"));
		}
	}

	@Test
	@DisplayName("장바구니 생성은 201과 cartId를 반환한다")
	void createReturnsCartId() throws Exception {
		long cartId = givenNewCart(7L);
		assertThat(cartId).isPositive();
	}
}
