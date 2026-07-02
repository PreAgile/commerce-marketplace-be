package com.lemong.marketplace.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.settlement.application.SettlementCloseService;
import com.lemong.marketplace.settlement.application.SettlementCloseService.CycleSummary;
import com.lemong.marketplace.settlement.domain.CycleType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 정산 사이클 마감 배치(S5 후반)를 실 Postgres로 검증한다: 미배정 라인 → 셀러별 사이클 귀속, 창 밖 라인 제외, 재마감
 * EXCLUDE 거부, 늦게 도착한 라인은 다음 마감이 잡음. 원장은 append-only라 마감은 cycle_id만 채우고 금액은 불변.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SettlementCloseIT {

	private static final OffsetDateTime WINDOW_START = OffsetDateTime.parse("2026-06-01T00:00:00Z");
	private static final OffsetDateTime WINDOW_END = OffsetDateTime.parse("2026-06-08T00:00:00Z");

	@Autowired
	SettlementCloseService close;

	@Autowired
	JdbcClient jdbc;

	private final AtomicLong seq = new AtomicLong();

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE settlement_line, settlement_cycle RESTART IDENTITY").update();
	}

	// 미배정(cycle_id NULL) 라인 하나 적재. 부호는 ck_settline_sign을 만족하는 값으로 호출자가 준다.
	private void insertLine(long sellerId, String entryType, long amount, OffsetDateTime eligibleAt) {
		long n = seq.incrementAndGet();
		jdbc.sql(
				"""
						INSERT INTO settlement_line
						    (seller_id, entry_type, source_type, source_id, source_event_id, amount_minor, eligible_at, cycle_id)
						VALUES (:s, :type, 'ORDER_ITEM', :sid, :evid, :amt, :at, NULL)
						""")
				.param("s", sellerId).param("type", entryType).param("sid", n).param("evid", "evt-" + n)
				.param("amt", amount).param("at", eligibleAt).update();
	}

	// 셀러 매출 한 벌(SALE + COMMISSION).
	private void sellerSale(long sellerId, long sale, long commission, OffsetDateTime at) {
		insertLine(sellerId, "SALE", sale, at);
		insertLine(sellerId, "COMMISSION", -commission, at);
	}

	private Long cycleIdOf(long lineSourceSeller, String entryType) {
		return jdbc.sql("SELECT cycle_id FROM settlement_line WHERE seller_id = :s AND entry_type = :t")
				.param("s", lineSourceSeller).param("t", entryType).query(Long.class).single();
	}

	@Test
	@DisplayName("창 안 미배정 라인이 셀러별 사이클에 귀속되고 순지급(SALE+COMMISSION)이 집계된다")
	void assignsUnassignedLinesToSellerCycle() {
		sellerSale(10, 1000, 150, WINDOW_START.plusDays(1)); // net 850
		sellerSale(20, 2000, 200, WINDOW_START.plusDays(2)); // net 1800

		List<CycleSummary> result = close.run(CycleType.WEEKLY, WINDOW_START, WINDOW_END);

		assertThat(result).hasSize(2);
		assertThat(result).extracting(CycleSummary::sellerId).containsExactly(10L, 20L); // seller_id 오름차순(락 순서)
		assertThat(result).extracting(CycleSummary::netMinor).containsExactly(850L, 1800L);
		assertThat(result).allSatisfy(c -> assertThat(c.lineCount()).isEqualTo(2));

		// 라인이 실제로 셀러 사이클에 귀속됐고 미배정이 남지 않았다.
		assertThat(cycleIdOf(10, "SALE")).isEqualTo(result.get(0).cycleId());
		assertThat(cycleIdOf(20, "SALE")).isEqualTo(result.get(1).cycleId());
		Long unassigned = jdbc.sql("SELECT count(*) FROM settlement_line WHERE cycle_id IS NULL").query(Long.class)
				.single();
		assertThat(unassigned).isZero();
	}

	@Test
	@DisplayName("창 밖(eligible_at) 라인은 마감에서 제외돼 미배정으로 남는다")
	void leavesOutOfWindowLinesUnassigned() {
		sellerSale(10, 1000, 100, WINDOW_START.plusDays(1)); // 창 안
		sellerSale(10, 500, 50, WINDOW_END.plusDays(1)); // 창 밖(다음 주)

		close.run(CycleType.WEEKLY, WINDOW_START, WINDOW_END);

		Long assigned = jdbc.sql("SELECT count(*) FROM settlement_line WHERE seller_id = 10 AND cycle_id IS NOT NULL")
				.query(Long.class).single();
		Long unassigned = jdbc.sql("SELECT count(*) FROM settlement_line WHERE seller_id = 10 AND cycle_id IS NULL")
				.query(Long.class).single();
		assertThat(assigned).isEqualTo(2L); // 창 안 SALE+COMMISSION
		assertThat(unassigned).isEqualTo(2L); // 창 밖은 그대로
	}

	@Test
	@DisplayName("같은 창을 겹쳐 다시 마감하면 EXCLUDE로 거부되고 부분 마감이 남지 않는다(전체 롤백)")
	void reRunOnClosedWindowRejected() {
		sellerSale(10, 1000, 100, WINDOW_START.plusDays(1));
		List<CycleSummary> first = close.run(CycleType.WEEKLY, WINDOW_START, WINDOW_END);
		long firstCycle = first.get(0).cycleId();

		// 재마감이 EXCLUDE를 실제로 트리거하려면 미배정 라인이 있어야 한다(없으면 사이클 INSERT 자체를 안 해 no-op).
		// 창 안(eligible < END)에 늦게 도착한 라인을 두고 같은 창을 다시 마감 → 겹치는 사이클 INSERT가 거부된다.
		sellerSale(10, 500, 50, WINDOW_START.plusDays(2));
		assertThatThrownBy(() -> close.run(CycleType.WEEKLY, WINDOW_START, WINDOW_END))
				.isInstanceOf(DataIntegrityViolationException.class);

		// 첫 마감은 살아있고 사이클은 한 개뿐. 롤백으로 늦은 라인은 미배정으로 남는다(부분 마감 없음).
		Long cycles = jdbc.sql("SELECT count(*) FROM settlement_cycle WHERE seller_id = 10").query(Long.class).single();
		assertThat(cycles).isEqualTo(1L);
		Long assignedToFirst = jdbc.sql("SELECT count(*) FROM settlement_line WHERE seller_id = 10 AND cycle_id = :c")
				.param("c", firstCycle).query(Long.class).single();
		assertThat(assignedToFirst).isEqualTo(2L); // 첫 마감분 SALE+COMMISSION 그대로
		Long unassigned = jdbc.sql("SELECT count(*) FROM settlement_line WHERE seller_id = 10 AND cycle_id IS NULL")
				.query(Long.class).single();
		assertThat(unassigned).isEqualTo(2L); // 롤백된 늦은 라인
	}

	@Test
	@DisplayName("마감 뒤 늦게 도착한 라인은 미배정으로 남아 다음(인접) 마감이 cutoff 스윕으로 흡수한다")
	void lateArrivalPickedUpByNextClose() {
		sellerSale(10, 1000, 100, WINDOW_START.plusDays(1));
		close.run(CycleType.WEEKLY, WINDOW_START, WINDOW_END);

		// 택배사 콜백 지연: eligible_at이 이미 마감된 창 안(START+2)이지만 마감 후 도착.
		sellerSale(10, 300, 30, WINDOW_START.plusDays(2));
		Long strandedBefore = jdbc.sql("SELECT count(*) FROM settlement_line WHERE cycle_id IS NULL").query(Long.class)
				.single();
		assertThat(strandedBefore).isEqualTo(2L);

		// 다음 인접 창(반열림 [)이라 겹치지 않음)의 cutoff는 nextEnd. 늦은 라인은 eligible_at < nextEnd 이고
		// 아직 미배정이라 이 마감이 잡는다 — 하한 없는 스윕이라 과거 eligible_at도 흡수된다(고립 없음).
		OffsetDateTime nextEnd = WINDOW_END.plusDays(7);
		List<CycleSummary> second = close.run(CycleType.WEEKLY, WINDOW_END, nextEnd);

		assertThat(second).hasSize(1);
		assertThat(second.get(0).netMinor()).isEqualTo(270L); // 300 − 30
		Long stranded = jdbc.sql("SELECT count(*) FROM settlement_line WHERE cycle_id IS NULL").query(Long.class)
				.single();
		assertThat(stranded).isZero();
	}
}
