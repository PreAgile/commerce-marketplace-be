package com.lemong.marketplace.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.settlement.application.SellerSettlementView;
import com.lemong.marketplace.settlement.application.SettlementCloseService;
import com.lemong.marketplace.settlement.application.SettlementQueryService;
import com.lemong.marketplace.settlement.domain.CycleType;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 셀러 정산 조회 읽기 모델(GET /sellers/{id}/settlements)을 실 Postgres로 검증한다: 마감 사이클별 순지급
 * + 미배정(pending) 순액 + 전체 합이 부호 원장 SUM으로 도출됨을 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SellerSettlementQueryIT {

	private static final OffsetDateTime WINDOW_START = OffsetDateTime.parse("2026-06-01T00:00:00Z");
	private static final OffsetDateTime WINDOW_END = OffsetDateTime.parse("2026-06-08T00:00:00Z");

	@Autowired
	SettlementCloseService close;

	@Autowired
	SettlementQueryService query;

	@Autowired
	JdbcClient jdbc;

	private final AtomicLong seq = new AtomicLong();

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE settlement_line, settlement_cycle RESTART IDENTITY").update();
	}

	private void sellerSale(long sellerId, long sale, long commission, OffsetDateTime at) {
		insertLine(sellerId, "SALE", sale, at);
		insertLine(sellerId, "COMMISSION", -commission, at);
	}

	private void insertLine(long sellerId, String entryType, long amount, OffsetDateTime at) {
		long n = seq.incrementAndGet();
		jdbc.sql(
				"""
						INSERT INTO settlement_line
						    (seller_id, entry_type, source_type, source_id, source_event_id, amount_minor, eligible_at, cycle_id)
						VALUES (:s, :type, 'ORDER_ITEM', :sid, :evid, :amt, :at, NULL)
						""")
				.param("s", sellerId).param("type", entryType).param("sid", n).param("evid", "evt-" + n)
				.param("amt", amount).param("at", at).update();
	}

	@Test
	@DisplayName("마감 사이클 순지급과 미배정 pending이 분리 집계되고 전체 합이 도출된다")
	void reportsCyclesAndPending() {
		sellerSale(10, 1000, 150, WINDOW_START.plusDays(1)); // 마감 대상, net 850
		close.run(CycleType.WEEKLY, WINDOW_START, WINDOW_END);
		sellerSale(10, 400, 40, WINDOW_END.plusDays(1)); // 아직 미마감(다음 창), net 360 → pending

		SellerSettlementView view = query.forSeller(10);

		assertThat(view.sellerId()).isEqualTo(10L);
		assertThat(view.cycles()).hasSize(1);
		var cycle = view.cycles().get(0);
		assertThat(cycle.cycleType()).isEqualTo(CycleType.WEEKLY);
		assertThat(cycle.status()).isEqualTo("CLOSED");
		assertThat(cycle.lineCount()).isEqualTo(2);
		assertThat(cycle.netMinor()).isEqualTo(850L);
		assertThat(view.pendingNetMinor()).isEqualTo(360L);
		assertThat(view.totalNetMinor()).isEqualTo(1210L); // 850 + 360
	}

	@Test
	@DisplayName("사이클도 라인도 없는 셀러는 빈 조회를 돌려준다(순액 0)")
	void emptyForUnknownSeller() {
		SellerSettlementView view = query.forSeller(999);

		assertThat(view.cycles()).isEmpty();
		assertThat(view.pendingNetMinor()).isZero();
		assertThat(view.totalNetMinor()).isZero();
	}
}
