package com.lemong.marketplace.payment;

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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PaymentAcceptanceIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE outbox, payment RESTART IDENTITY CASCADE").update();
	}

	private long initiate(long orderId, String idemKey, long amount) throws Exception {
		String body = mvc
				.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(
						"{\"orderId\":%d,\"idempotencyKey\":\"%s\",\"amount\":%d}".formatted(orderId, idemKey, amount)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.parse(body).read("$.paymentId")).longValue();
	}

	private void confirm(long paymentId) throws Exception {
		mvc.perform(post("/payments/" + paymentId + "/confirm")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PAID"));
	}

	private int outboxCount(long paymentId) {
		return jdbc.sql(
				"SELECT count(*) FROM outbox WHERE aggregate_type='payment' AND aggregate_id=:id AND event_type='PaymentConfirmed'")
				.param("id", paymentId).query(Integer.class).single();
	}

	@Nested
	@DisplayName("결제를 확정할 때")
	class Confirming {

		@Test
		@DisplayName("Given 선점된 결제, When 확정하면, Then PAID가 되고 outbox에 PaymentConfirmed 1건이 적재된다")
		void confirmTurnsPaidAndWritesOutbox() throws Exception {
			long paymentId = initiate(1L, "idem-1", 10_000L);

			confirm(paymentId);

			mvc.perform(get("/payments/" + paymentId)).andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("PAID")).andExpect(jsonPath("$.paidAmount").value(10_000));
			org.assertj.core.api.Assertions.assertThat(outboxCount(paymentId)).isEqualTo(1);
		}

		@Test
		@DisplayName("같은 결제를 두 번 확정해도(따닥/재시도) PAID·outbox 1건으로 멱등하다")
		void confirmIsIdempotent() throws Exception {
			long paymentId = initiate(1L, "idem-1", 10_000L);

			confirm(paymentId);
			confirm(paymentId); // 재시도

			org.assertj.core.api.Assertions.assertThat(outboxCount(paymentId)).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("결제를 선점할 때")
	class Initiating {

		@Test
		@DisplayName("같은 멱등키로 두 번 선점하면 같은 결제가 반환된다(중복 생성 없음)")
		void initiateIsIdempotentByKey() throws Exception {
			long first = initiate(1L, "same-key", 5_000L);
			long second = initiate(1L, "same-key", 5_000L);

			org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
			org.assertj.core.api.Assertions
					.assertThat(jdbc.sql("SELECT count(*) FROM payment").query(Integer.class).single()).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("잘못된 요청")
	class InvalidRequests {

		@Test
		@DisplayName("금액이 0 이하면 400")
		void nonPositiveAmountIsBadRequest() throws Exception {
			mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
					.content("{\"orderId\":1,\"idempotencyKey\":\"k\",\"amount\":0}"))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("존재하지 않는 결제를 확정하면 404")
		void confirmingMissingPaymentIsNotFound() throws Exception {
			mvc.perform(post("/payments/999999/confirm")).andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("존재하지 않는 결제 조회는 404")
		void getMissingPaymentIsNotFound() throws Exception {
			mvc.perform(get("/payments/999999")).andExpect(status().isNotFound());
		}
	}
}
