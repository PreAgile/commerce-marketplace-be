package com.lemong.marketplace.settlement;

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
 * 정산 마감·조회 인수 테스트 — 골든 시나리오 S5. 마감 배치(POST /admin/settlements/run)와 셀러 조회(GET
 * /sellers/{id}/settlements)가 풀스택(API→실 DB)에서 동작하고, 3층 검증의 HTTP 매핑(400/409)이
 * 맞는지 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SettlementAcceptanceIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	private long seq;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE settlement_line, settlement_cycle RESTART IDENTITY").update();
		seq = 0;
	}

	// 미배정 매출 한 벌(SALE + COMMISSION), eligible_at는 6월 첫 주 안.
	private void sellerSale(long sellerId, long sale, long commission) {
		insertLine(sellerId, "SALE", sale);
		insertLine(sellerId, "COMMISSION", -commission);
	}

	private void insertLine(long sellerId, String entryType, long amount) {
		long n = ++seq;
		jdbc.sql(
				"""
						INSERT INTO settlement_line
						    (seller_id, entry_type, source_type, source_id, source_event_id, amount_minor, eligible_at, cycle_id)
						VALUES (:s, :type, 'ORDER_ITEM', :sid, :evid, :amt, '2026-06-02T00:00:00Z', NULL)
						""")
				.param("s", sellerId).param("type", entryType).param("sid", n).param("evid", "evt-" + n)
				.param("amt", amount).update();
	}

	private String runBody(String start, String end) {
		return "{\"cycleType\":\"weekly\",\"periodStart\":\"%s\",\"periodEnd\":\"%s\"}".formatted(start, end);
	}

	@Nested
	@DisplayName("마감 배치를 실행할 때")
	class RunClose {

		@Test
		@DisplayName("닫힌 창을 마감하면 셀러별 사이클과 순지급이 응답에 담긴다")
		void closesAndReports() throws Exception {
			sellerSale(10, 1000, 150); // net 850
			sellerSale(20, 2000, 200); // net 1800

			mvc.perform(post("/admin/settlements/run").contentType(MediaType.APPLICATION_JSON)
					.content(runBody("2026-06-01T00:00:00Z", "2026-06-08T00:00:00Z"))).andExpect(status().isOk())
					.andExpect(jsonPath("$.cycles.length()").value(2))
					.andExpect(jsonPath("$.cycles[0].sellerId").value(10))
					.andExpect(jsonPath("$.cycles[0].netMinor").value(850))
					.andExpect(jsonPath("$.cycles[1].sellerId").value(20))
					.andExpect(jsonPath("$.cycles[1].netMinor").value(1800));
		}

		@Test
		@DisplayName("반열림이 아닌 창(end <= start)은 400")
		void invertedWindowRejected() throws Exception {
			mvc.perform(post("/admin/settlements/run").contentType(MediaType.APPLICATION_JSON)
					.content(runBody("2026-06-08T00:00:00Z", "2026-06-01T00:00:00Z")))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("cycleType 누락은 400")
		void missingCycleType() throws Exception {
			mvc.perform(post("/admin/settlements/run").contentType(MediaType.APPLICATION_JSON)
					.content("{\"periodStart\":\"2026-06-01T00:00:00Z\",\"periodEnd\":\"2026-06-08T00:00:00Z\"}"))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("같은 창을 겹쳐 다시 마감하면 EXCLUDE 최후보루로 409")
		void overlappingReRunConflicts() throws Exception {
			sellerSale(10, 1000, 100);
			mvc.perform(post("/admin/settlements/run").contentType(MediaType.APPLICATION_JSON)
					.content(runBody("2026-06-01T00:00:00Z", "2026-06-08T00:00:00Z"))).andExpect(status().isOk());

			// 재마감이 사이클 INSERT를 시도하도록 미배정 라인을 하나 더 둔다(창 안).
			sellerSale(10, 300, 30);
			mvc.perform(post("/admin/settlements/run").contentType(MediaType.APPLICATION_JSON)
					.content(runBody("2026-06-01T00:00:00Z", "2026-06-08T00:00:00Z"))).andExpect(status().isConflict());
		}
	}

	@Nested
	@DisplayName("셀러 정산을 조회할 때")
	class QuerySettlements {

		@Test
		@DisplayName("마감 순지급과 미배정 pending이 분리 집계돼 반환된다")
		void reportsCyclesAndPending() throws Exception {
			sellerSale(10, 1000, 150); // 마감 대상, net 850
			mvc.perform(post("/admin/settlements/run").contentType(MediaType.APPLICATION_JSON)
					.content(runBody("2026-06-01T00:00:00Z", "2026-06-08T00:00:00Z"))).andExpect(status().isOk());

			mvc.perform(get("/sellers/10/settlements")).andExpect(status().isOk())
					.andExpect(jsonPath("$.sellerId").value(10)).andExpect(jsonPath("$.cycles.length()").value(1))
					.andExpect(jsonPath("$.cycles[0].cycleType").value("weekly"))
					.andExpect(jsonPath("$.cycles[0].status").value("CLOSED"))
					.andExpect(jsonPath("$.cycles[0].netMinor").value(850))
					.andExpect(jsonPath("$.pendingNetMinor").value(0))
					.andExpect(jsonPath("$.totalNetMinor").value(850));
		}

		@Test
		@DisplayName("정산 이력이 없는 셀러는 빈 조회(순액 0)")
		void emptyForUnknownSeller() throws Exception {
			mvc.perform(get("/sellers/999/settlements")).andExpect(status().isOk())
					.andExpect(jsonPath("$.cycles.length()").value(0)).andExpect(jsonPath("$.totalNetMinor").value(0));
		}
	}
}
