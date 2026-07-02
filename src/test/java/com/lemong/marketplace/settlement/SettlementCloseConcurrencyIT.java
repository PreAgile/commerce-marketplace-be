package com.lemong.marketplace.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.settlement.application.SettlementCloseService;
import com.lemong.marketplace.settlement.domain.CycleType;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 따닥 동시 마감: 같은 셀러·유형·창을 N스레드가 동시에 마감해도 사이클은 정확히 1개만 생기고 라인은 그 한 사이클에만 귀속되어야
 * 한다(이중 집계 0). {@code ex_cycle_no_overlap} EXCLUDE가 두 번째 마감 INSERT를 겹침으로 거부하고,
 * {@code cycle_id IS NULL} 조건부 UPDATE가 라인 재귀속을 막는다 — 코드 락 없이 DB 구조만으로 직렬화됨을
 * 증명한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SettlementCloseConcurrencyIT {

	private static final OffsetDateTime WINDOW_START = OffsetDateTime.parse("2026-06-01T00:00:00Z");
	private static final OffsetDateTime WINDOW_END = OffsetDateTime.parse("2026-06-08T00:00:00Z");
	private static final long SELLER = 10;

	@Autowired
	SettlementCloseService close;

	@Autowired
	JdbcClient jdbc;

	private final AtomicLong seq = new AtomicLong();

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE settlement_line, settlement_cycle RESTART IDENTITY").update();
	}

	private void insertLine(String entryType, long amount) {
		long n = seq.incrementAndGet();
		jdbc.sql(
				"""
						INSERT INTO settlement_line
						    (seller_id, entry_type, source_type, source_id, source_event_id, amount_minor, eligible_at, cycle_id)
						VALUES (:s, :type, 'ORDER_ITEM', :sid, :evid, :amt, :at, NULL)
						""")
				.param("s", SELLER).param("type", entryType).param("sid", n).param("evid", "evt-" + n)
				.param("amt", amount).param("at", WINDOW_START.plusDays(1)).update();
	}

	@Test
	@DisplayName("16스레드가 같은 창을 동시에 마감해도 사이클 1개·라인 귀속 1벌, 이중 집계는 없다")
	void concurrentCloseCreatesExactlyOneCycle() throws InterruptedException {
		insertLine("SALE", 1000);
		insertLine("COMMISSION", -100); // net 900, 2줄

		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicInteger committed = new AtomicInteger();
		AtomicInteger excluded = new AtomicInteger();
		AtomicReference<Throwable> unexpected = new AtomicReference<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					try {
						start.await();
						close.run(CycleType.WEEKLY, WINDOW_START, WINDOW_END);
						committed.incrementAndGet(); // 마감 성공(패자가 미배정 0을 보고 no-op으로 성공하는 경우 포함)
					} catch (DataIntegrityViolationException e) {
						excluded.incrementAndGet(); // EXCLUDE가 중복 창 마감을 거부(기대된 패배)
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						unexpected.compareAndSet(null, e);
					} catch (Throwable e) {
						unexpected.compareAndSet(null, e);
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			done.await();
		}

		assertThat(unexpected.get()).as("패자는 EXCLUDE 거부 외의 이유로 죽으면 안 된다").isNull();
		assertThat(committed.get() + excluded.get()).isEqualTo(threads);

		// 핵심 불변식: 셀러 사이클은 정확히 1개, 라인 2줄 모두 그 사이클에 귀속, net 보존.
		Long cycles = jdbc.sql("SELECT count(*) FROM settlement_cycle WHERE seller_id = :s").param("s", SELLER)
				.query(Long.class).single();
		assertThat(cycles).isEqualTo(1L);

		Long distinctCycleIds = jdbc.sql(
				"SELECT count(DISTINCT cycle_id) FROM settlement_line WHERE seller_id = :s AND cycle_id IS NOT NULL")
				.param("s", SELLER).query(Long.class).single();
		assertThat(distinctCycleIds).isEqualTo(1L);

		Long unassigned = jdbc.sql("SELECT count(*) FROM settlement_line WHERE cycle_id IS NULL").query(Long.class)
				.single();
		assertThat(unassigned).isZero();

		Long net = jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM settlement_line WHERE seller_id = :s")
				.param("s", SELLER).query(Long.class).single();
		assertThat(net).isEqualTo(900L); // 마감이 금액을 변조하지 않는다
	}

	@Test
	@DisplayName("서로 다른 유형(weekly·express)이 같은 미배정 풀을 동시에 마감해도 라인 0개짜리 고스트 사이클이 안 남는다")
	void concurrentCrossTypeCloseLeavesNoGhostCycle() throws InterruptedException {
		// weekly·express는 cycle_type이 달라 EXCLUDE가 서로를 막지 않는다. 후보 조회 시점엔 둘 다 미배정 라인을 보지만
		// 한쪽이 라인을 선점하면 다른 쪽 UPDATE는 0건 — 가드가 없으면 방금 만든 사이클이 라인 0개로 봉인돼 남는다(TOCTOU).
		insertLine("SALE", 1000);
		insertLine("COMMISSION", -100);

		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		AtomicReference<Throwable> unexpected = new AtomicReference<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			for (CycleType type : new CycleType[]{CycleType.WEEKLY, CycleType.EXPRESS}) {
				pool.submit(() -> {
					try {
						start.await();
						close.run(type, WINDOW_START, WINDOW_END);
					} catch (DataIntegrityViolationException e) {
						// 같은 유형 아님 → EXCLUDE는 안 뜬다. 떠도 회귀는 아래 불변식이 잡는다.
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						unexpected.compareAndSet(null, e);
					} catch (Throwable e) {
						unexpected.compareAndSet(null, e);
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			done.await();
		}

		assertThat(unexpected.get()).isNull();
		// 핵심 불변식: 라인이 귀속된 사이클만 남는다 — 어떤 CLOSED 사이클도 라인 0개가 아니다.
		Long ghostCycles = jdbc.sql("""
				SELECT count(*) FROM settlement_cycle c
				WHERE NOT EXISTS (SELECT 1 FROM settlement_line l WHERE l.cycle_id = c.cycle_id)
				""").query(Long.class).single();
		assertThat(ghostCycles).as("라인 0개짜리 고스트 사이클이 남으면 안 된다").isZero();
		// 라인은 정확히 한 사이클에만 귀속(이중 집계 0), 미배정 0.
		Long distinctCycleIds = jdbc
				.sql("SELECT count(DISTINCT cycle_id) FROM settlement_line WHERE cycle_id IS NOT NULL")
				.query(Long.class).single();
		assertThat(distinctCycleIds).isEqualTo(1L);
		Long unassigned = jdbc.sql("SELECT count(*) FROM settlement_line WHERE cycle_id IS NULL").query(Long.class)
				.single();
		assertThat(unassigned).isZero();
	}
}
