package com.lemong.marketplace.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * 배송 상태 전이 인수 테스트 — 골든 시나리오 S4. 상태머신 화이트리스트가 풀스택(API→DB)에서 강제되고, 전이가
 * append-only로 쌓이며 현재 상태가 단 하나임을 실 Postgres로 검증한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ShipmentTransitionIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	private long seq;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE shipment_event, shipment RESTART IDENTITY").update();
		seq = 0;
	}

	/** 배송 생성(M3-a)을 직접 모사한다: READY shipment + 최초 most_recent 이력. */
	private long seedReadyShipment() {
		long id = jdbc.sql("""
				INSERT INTO shipment (order_id, seller_id, source_event_id, status)
				VALUES (1, 10, :evid, 'READY') RETURNING id
				""").param("evid", "evt-" + (++seq)).query(Long.class).single();
		jdbc.sql("""
				INSERT INTO shipment_event (shipment_id, from_status, to_status, most_recent, sort_key, occurred_at)
				VALUES (:id, NULL, 'READY', TRUE, 0, now())
				""").param("id", id).update();
		return id;
	}

	private org.springframework.test.web.servlet.ResultActions transition(long id, String status) throws Exception {
		return mvc.perform(post("/shipments/" + id + "/events").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"" + status + "\"}"));
	}

	/** 거부된 전이는 완전한 no-op이어야 한다: 현재 상태·이력 길이 불변, 현재 상태(most_recent)는 여전히 단 하나. */
	private void assertUnchanged(long id, String expectedStatus, long expectedHistory) {
		String status = jdbc.sql("SELECT status FROM shipment WHERE id = :id").param("id", id).query(String.class)
				.single();
		assertThat(status).isEqualTo(expectedStatus);
		Long total = jdbc.sql("SELECT count(*) FROM shipment_event WHERE shipment_id = :id").param("id", id)
				.query(Long.class).single();
		assertThat(total).isEqualTo(expectedHistory);
		Long mostRecent = jdbc.sql("SELECT count(*) FROM shipment_event WHERE shipment_id = :id AND most_recent")
				.param("id", id).query(Long.class).single();
		assertThat(mostRecent).isEqualTo(1L);
	}

	@Nested
	@DisplayName("합법 전이를 기록할 때")
	class LegalTransitions {

		@Test
		@DisplayName("해피패스 READY→PICKED_UP→IN_TRANSIT→OUT_FOR_DELIVERY→DELIVERED가 순서대로 통과한다")
		void happyPathChain() throws Exception {
			long id = seedReadyShipment();
			for (String next : new String[]{"PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"}) {
				transition(id, next).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(next));
			}

			mvc.perform(get("/shipments/" + id)).andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("DELIVERED"))
					.andExpect(jsonPath("$.history.length()").value(5))
					.andExpect(jsonPath("$.history[0].to").value("READY"))
					.andExpect(jsonPath("$.history[4].from").value("OUT_FOR_DELIVERY"))
					.andExpect(jsonPath("$.history[4].to").value("DELIVERED"));

			// append-only 불변식: 현재 상태는 단 하나, sort_key는 0..4 연속
			Long mostRecent = jdbc.sql("SELECT count(*) FROM shipment_event WHERE shipment_id = :id AND most_recent")
					.param("id", id).query(Long.class).single();
			assertThat(mostRecent).isEqualTo(1L);
			Long maxSort = jdbc.sql("SELECT max(sort_key) FROM shipment_event WHERE shipment_id = :id").param("id", id)
					.query(Long.class).single();
			assertThat(maxSort).isEqualTo(4L);
		}

		@Test
		@DisplayName("어느 단계에서든 FAILED로 빠질 수 있다")
		void canFailFromInTransit() throws Exception {
			long id = seedReadyShipment();
			transition(id, "PICKED_UP").andExpect(status().isOk());
			transition(id, "IN_TRANSIT").andExpect(status().isOk());
			transition(id, "FAILED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
		}
	}

	@Nested
	@DisplayName("불법 전이를 거부할 때 (409)")
	class IllegalTransitions {

		@Test
		@DisplayName("단계 건너뛰기 READY→DELIVERED는 409")
		void skipRejected() throws Exception {
			long id = seedReadyShipment();
			transition(id, "DELIVERED").andExpect(status().isConflict());
			assertUnchanged(id, "READY", 1);
		}

		@Test
		@DisplayName("역전이 PICKED_UP→READY는 409")
		void backwardRejected() throws Exception {
			long id = seedReadyShipment();
			transition(id, "PICKED_UP").andExpect(status().isOk());
			transition(id, "READY").andExpect(status().isConflict());
			assertUnchanged(id, "PICKED_UP", 2);
		}

		@Test
		@DisplayName("자가 전이 READY→READY는 409")
		void selfRejected() throws Exception {
			long id = seedReadyShipment();
			transition(id, "READY").andExpect(status().isConflict());
			assertUnchanged(id, "READY", 1);
		}

		@Test
		@DisplayName("종료 상태 DELIVERED에서의 전이는 409")
		void fromTerminalRejected() throws Exception {
			long id = seedReadyShipment();
			for (String next : new String[]{"PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"}) {
				transition(id, next).andExpect(status().isOk());
			}
			transition(id, "PICKED_UP").andExpect(status().isConflict());
			assertUnchanged(id, "DELIVERED", 5);
		}
	}

	@Nested
	@DisplayName("잘못된 요청일 때")
	class BadRequests {

		@Test
		@DisplayName("존재하지 않는 배송 전이는 404")
		void missingShipment() throws Exception {
			transition(999_999L, "PICKED_UP").andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("정의되지 않은 상태 문자열은 400")
		void invalidStatus() throws Exception {
			long id = seedReadyShipment();
			transition(id, "TELEPORTED").andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("status 누락은 400")
		void missingStatus() throws Exception {
			long id = seedReadyShipment();
			mvc.perform(post("/shipments/" + id + "/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andExpect(status().isBadRequest());
		}
	}
}
