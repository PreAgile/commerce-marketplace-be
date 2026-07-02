package com.lemong.marketplace.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 부분환불 인수 테스트 — 골든 시나리오 S6. {@code POST /orders/{id}/refunds}가 풀스택(API→실 DB)에서
 * 동작하고 3층 검증의 HTTP 매핑(200/400/404/409)이 맞는지 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RefundAcceptanceIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE refund, outbox, payment RESTART IDENTITY CASCADE").update();
	}

	private void seedPayment(long orderId, long paid, String status) {
		jdbc.sql("""
				INSERT INTO payment (order_id, idempotency_key, paid_amount, status)
				VALUES (:oid, :key, :paid, :status)
				""").param("oid", orderId).param("key", "pay-" + orderId).param("paid", paid).param("status", status)
				.update();
	}

	private String body(long line, long amount, String key) {
		return "{\"orderLineId\":%d,\"amount\":%d,\"idempotencyKey\":\"%s\"}".formatted(line, amount, key);
	}

	@Nested
	@DisplayName("환불 요청이 성공할 때")
	class Success {

		@Test
		@DisplayName("부분환불 → 200 + 누적환불·환불가능액을 도출해 반환한다")
		void partialRefund() throws Exception {
			seedPayment(300, 1000, "PAID");
			mvc.perform(
					post("/orders/300/refunds").contentType(MediaType.APPLICATION_JSON).content(body(10, 400, "k1")))
					.andExpect(status().isOk()).andExpect(jsonPath("$.paidMinor").value(1000))
					.andExpect(jsonPath("$.refundedMinor").value(400))
					.andExpect(jsonPath("$.refundableMinor").value(600));
		}
	}

	@Nested
	@DisplayName("환불 요청이 거부될 때")
	class Rejected {

		@Test
		@DisplayName("결제금액 초과 환불은 409")
		void overRefundConflicts() throws Exception {
			seedPayment(301, 1000, "PAID");
			mvc.perform(
					post("/orders/301/refunds").contentType(MediaType.APPLICATION_JSON).content(body(10, 700, "k1")))
					.andExpect(status().isOk());
			mvc.perform(
					post("/orders/301/refunds").contentType(MediaType.APPLICATION_JSON).content(body(10, 400, "k2")))
					.andExpect(status().isConflict());
		}

		@Test
		@DisplayName("PAID가 아닌 결제 환불은 409")
		void nonPaidConflicts() throws Exception {
			seedPayment(302, 1000, "PENDING");
			mvc.perform(
					post("/orders/302/refunds").contentType(MediaType.APPLICATION_JSON).content(body(10, 100, "k1")))
					.andExpect(status().isConflict());
		}

		@Test
		@DisplayName("결제 없는 주문 환불은 404")
		void missingPaymentNotFound() throws Exception {
			mvc.perform(
					post("/orders/999999/refunds").contentType(MediaType.APPLICATION_JSON).content(body(10, 100, "k1")))
					.andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("필수값 누락(amount)·양수 위반은 400")
		void badRequest() throws Exception {
			seedPayment(303, 1000, "PAID");
			mvc.perform(post("/orders/303/refunds").contentType(MediaType.APPLICATION_JSON)
					.content("{\"orderLineId\":10,\"idempotencyKey\":\"k1\"}")).andExpect(status().isBadRequest());
			mvc.perform(post("/orders/303/refunds").contentType(MediaType.APPLICATION_JSON).content(body(10, 0, "k2")))
					.andExpect(status().isBadRequest());
		}
	}
}
