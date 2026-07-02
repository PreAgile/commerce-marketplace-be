package com.lemong.marketplace.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.settlement.application.SettlementCloseService;
import com.lemong.marketplace.settlement.application.SettlementCloseService.CycleSummary;
import com.lemong.marketplace.settlement.application.SettlementService;
import com.lemong.marketplace.settlement.domain.CycleType;
import java.time.OffsetDateTime;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 정산 마감 보존 불변식을 property로 폭격한다. 랜덤 멀티셀러 매출(SALE+/COMMISSION−)을 쌓고 마감한 뒤:
 * <ul>
 * <li>마감은 금액을 만들거나 없애지 않는다 — 원장 SUM 불변(cycle_id만 채움).
 * <li>모든 대상 라인이 정확히 한 사이클에 귀속(미배정 0).
 * <li>셀러 순지급은 음수가 아니다(수수료율 ≤ 100% → COMMISSION ≤ SALE).
 * <li><b>Σ셀러정산 + Σ수수료 == 총매출</b> — 이 프로젝트 헤드라인 항등식.
 * </ul>
 */
@JqwikSpringSupport
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SettlementConservationPropertyTest {

	private static final OffsetDateTime WINDOW_START = OffsetDateTime.parse("2026-06-01T00:00:00Z");
	private static final OffsetDateTime WINDOW_END = OffsetDateTime.parse("2026-06-08T00:00:00Z");

	@Autowired
	SettlementCloseService close;

	@Autowired
	JdbcClient jdbc;

	record SaleSpec(long sale, int rateBps) {
	}

	@Provide
	Arbitrary<List<SaleSpec>> sales() {
		Arbitrary<SaleSpec> one = Combinators
				.combine(Arbitraries.longs().between(1, 1_000_000), Arbitraries.integers().between(0, 10_000))
				.as(SaleSpec::new);
		return one.list().ofMinSize(1).ofMaxSize(6);
	}

	@Property(tries = 200)
	void closePreservesLedgerAndConservesTotal(@ForAll @From("sales") List<SaleSpec> specs) {
		jdbc.sql("TRUNCATE TABLE settlement_line, settlement_cycle RESTART IDENTITY").update();

		long totalSales = 0;
		long totalCommission = 0;
		for (int i = 0; i < specs.size(); i++) {
			long sellerId = i + 1;
			SaleSpec spec = specs.get(i);
			long commission = SettlementService.commissionMinor(spec.sale(), spec.rateBps());
			insertLine(sellerId, "SALE", spec.sale(), i);
			if (commission > 0) {
				insertLine(sellerId, "COMMISSION", -commission, i);
			}
			totalSales += spec.sale();
			totalCommission += commission;
		}
		long ledgerBefore = ledgerSum();

		List<CycleSummary> result = close.run(CycleType.WEEKLY, WINDOW_START, WINDOW_END);

		// 마감은 금액을 변조하지 않는다.
		assertThat(ledgerSum()).isEqualTo(ledgerBefore);
		// 모든 라인이 귀속됐다(미배정 0).
		assertThat(count("cycle_id IS NULL")).isZero();
		// 셀러 순지급은 음수가 아니다.
		assertThat(result).allSatisfy(c -> assertThat(c.netMinor()).isGreaterThanOrEqualTo(0));
		// 헤드라인 항등식: Σ셀러정산 + Σ수수료 == 총매출.
		long closedNet = result.stream().mapToLong(CycleSummary::netMinor).sum();
		assertThat(closedNet + totalCommission).isEqualTo(totalSales);
		// 이중 확인: 매출 라인 합 == 총매출, 수수료 라인 합 == −총수수료.
		assertThat(sum("entry_type = 'SALE'")).isEqualTo(totalSales);
		assertThat(sum("entry_type = 'COMMISSION'")).isEqualTo(-totalCommission);
	}

	private void insertLine(long sellerId, String entryType, long amount, int i) {
		// source_id는 (셀러 × 유형) 유일하면 되고, source_event_id는 전역 유일하면 된다.
		jdbc.sql(
				"""
						INSERT INTO settlement_line
						    (seller_id, entry_type, source_type, source_id, source_event_id, amount_minor, eligible_at, cycle_id)
						VALUES (:s, :type, 'ORDER_ITEM', :sid, :evid, :amt, :at, NULL)
						""")
				.param("s", sellerId).param("type", entryType).param("sid", sellerId)
				.param("evid", entryType + ":" + sellerId + ":" + i).param("amt", amount).param("at", WINDOW_START)
				.update();
	}

	private long ledgerSum() {
		return sum("TRUE");
	}

	private long sum(String where) {
		return jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM settlement_line WHERE " + where).query(Long.class)
				.single();
	}

	private long count(String where) {
		return jdbc.sql("SELECT count(*) FROM settlement_line WHERE " + where).query(Long.class).single();
	}
}
