package com.lemong.marketplace.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.payment.application.RefundService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 따닥 환불 동시성: payment 행 {@code FOR UPDATE} 직렬화(ADR-005)와 상한 트리거가 함께, 어떤 경합에서도
 * 누적환불이 결제금액을 넘지 못하게 함을 증명한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RefundConcurrencyIT {

	@Autowired
	RefundService refunds;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE refund, outbox, payment RESTART IDENTITY CASCADE").update();
	}

	private long seedPaid(long orderId, long paid) {
		return jdbc.sql("""
				INSERT INTO payment (order_id, idempotency_key, paid_amount, status)
				VALUES (:oid, :key, :paid, 'PAID') RETURNING id
				""").param("oid", orderId).param("key", "pay-" + orderId).param("paid", paid).query(Long.class)
				.single();
	}

	private long refundSum(long pid) {
		return jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM refund WHERE payment_id = :pid").param("pid", pid)
				.query(Long.class).single();
	}

	@Test
	@DisplayName("16스레드가 같은 토큰으로 동시 환불해도 원장·이벤트는 한 벌만(멱등)")
	void concurrentSameTokenRefundsOnce() throws InterruptedException {
		long orderId = 200;
		long pid = seedPaid(orderId, 1000);
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicReference<Throwable> unexpected = new AtomicReference<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					try {
						start.await();
						refunds.refund(orderId, 10L, 400, "same-token");
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

		assertThat(unexpected.get()).as("멱등 환불은 예외 없이 no-op이어야 한다").isNull();
		Long rows = jdbc.sql("SELECT count(*) FROM refund WHERE payment_id = :pid").param("pid", pid).query(Long.class)
				.single();
		assertThat(rows).isEqualTo(1L);
		assertThat(refundSum(pid)).isEqualTo(400L);
		Long events = jdbc.sql("SELECT count(*) FROM outbox WHERE event_type = 'PaymentRefunded'").query(Long.class)
				.single();
		assertThat(events).isEqualTo(1L);
	}

	@Test
	@DisplayName("서로 다른 토큰의 따닥 환불이 몰려도 누적환불은 결제금액을 넘지 못한다")
	void concurrentDistinctRefundsRespectCeiling() throws InterruptedException {
		long orderId = 201;
		long pid = seedPaid(orderId, 1000);
		int threads = 10; // 각 300원 → 3건(900)만 통과, 4번째부터 초과
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicInteger success = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		AtomicReference<Throwable> unexpected = new AtomicReference<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				int n = i;
				pool.submit(() -> {
					try {
						start.await();
						refunds.refund(orderId, 10L, 300, "tok-" + n);
						success.incrementAndGet();
					} catch (DataIntegrityViolationException e) {
						rejected.incrementAndGet(); // 상한 초과 = 기대된 거부
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
		// 핵심 불변식: 누적환불 ≤ 결제금액. 300 × 3 = 900 ≤ 1000 < 1200 이라 정확히 3건만 통과.
		assertThat(success.get()).isEqualTo(3);
		assertThat(rejected.get()).isEqualTo(threads - 3);
		assertThat(refundSum(pid)).isEqualTo(900L).isLessThanOrEqualTo(1000L);
	}
}
