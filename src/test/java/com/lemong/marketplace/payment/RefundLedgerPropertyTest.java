package com.lemong.marketplace.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lemong.marketplace.TestcontainersConfiguration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 돈 불변식 {@code 0 < 환불 ∧ 누적환불 ≤ 결제금액}을 property로 폭격한다. 랜덤 환불 시퀀스를 한 결제에 적재하며,
 * DB는 "이 환불을 더해도 누적이 결제금액을 안 넘고 금액이 양수일 때만" 받아들여야 한다. 예제(RefundConstraintsIT)가
 * 못 보는 (금액 × 누적) 조합을 수백 시나리오로 검증한다. payment.refunded_amount 컬럼 CHECK를 대체한 원장
 * 트리거 버전.
 */
@JqwikSpringSupport
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RefundLedgerPropertyTest {

	@Autowired
	JdbcClient jdbc;

	private final AtomicLong seq = new AtomicLong();

	@Property(tries = 150)
	void dbAcceptsRefundIffWithinPaid(@ForAll @LongRange(min = 1, max = 100_000) long paid,
			@ForAll @Size(min = 1, max = 8) List<@LongRange(min = -500, max = 40_000) @IntRange Long> amounts) {

		jdbc.sql("TRUNCATE TABLE refund, payment RESTART IDENTITY CASCADE").update();
		long pid = jdbc.sql("""
				INSERT INTO payment (order_id, idempotency_key, paid_amount, status)
				VALUES (1, :key, :paid, 'PAID') RETURNING id
				""").param("key", "pay-" + seq.incrementAndGet()).param("paid", paid).query(Long.class).single();

		long running = 0; // 지금까지 수용된 환불의 합(테스트 오라클)
		for (long amount : amounts) {
			boolean shouldAccept = amount > 0 && running + amount <= paid;
			if (shouldAccept) {
				assertThat(insert(pid, amount)).isEqualTo(1);
				running += amount;
			} else {
				assertThatThrownBy(() -> insert(pid, amount)).isInstanceOf(DataIntegrityViolationException.class);
			}
		}

		long dbSum = jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM refund WHERE payment_id = :pid")
				.param("pid", pid).query(Long.class).single();
		assertThat(dbSum).isEqualTo(running).isLessThanOrEqualTo(paid); // 원장 누적 == 수용분 합, 결제금액 이내
	}

	private int insert(long pid, long amount) {
		long n = seq.incrementAndGet(); // 매 시도 고유 멱등키 → UNIQUE 충돌 회피
		return jdbc.sql("""
				INSERT INTO refund (payment_id, order_line_id, amount_minor, source_event_id)
				VALUES (:pid, 10, :amt, :evid)
				""").param("pid", pid).param("amt", amount).param("evid", "prop-" + n).update();
	}
}
