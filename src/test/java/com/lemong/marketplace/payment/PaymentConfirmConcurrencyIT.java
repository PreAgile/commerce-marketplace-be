package com.lemong.marketplace.payment;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.payment.application.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentConfirmConcurrencyIT {

	private static final int THREADS = 16;

	@Autowired
	PaymentService payments;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE outbox, payment RESTART IDENTITY CASCADE").update();
	}

	@Test
	@DisplayName("같은 결제를 N스레드가 동시에 확정해도 PAID 1회·outbox PaymentConfirmed 1건이다")
	void concurrentConfirmIsIdempotent() throws Exception {
		long paymentId = payments.initiate(1L, "idem-1", 10_000L);

		AtomicInteger ok = new AtomicInteger();
		ConcurrentLinkedQueue<Throwable> losers = new ConcurrentLinkedQueue<>();
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREADS);
		try {
			for (int i = 0; i < THREADS; i++) {
				pool.submit(() -> {
					ready.countDown();
					try {
						start.await();
						payments.confirm(paymentId);
						ok.incrementAndGet();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} catch (RuntimeException loser) {
						losers.add(loser);
					} finally {
						done.countDown();
					}
				});
			}
			ready.await();
			start.countDown();
			assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdownNow();
		}

		// 결과 불변식: 결제는 PAID, outbox PaymentConfirmed는 정확히 1건(중복 발행 0).
		assertThat(jdbc.sql("SELECT status FROM payment WHERE id = :id").param("id", paymentId).query(String.class)
				.single()).isEqualTo("PAID");
		assertThat(jdbc.sql(
				"SELECT count(*) FROM outbox WHERE aggregate_type='payment' AND aggregate_id=:id AND event_type='PaymentConfirmed'")
				.param("id", paymentId).query(Integer.class).single()).isEqualTo(1);
		// 적어도 한 스레드는 성공해야 하고, 패배는 중복 적재 충돌(DataIntegrityViolation)이어야 한다.
		assertThat(ok.get()).isGreaterThanOrEqualTo(1);
		assertThat(losers).allSatisfy(
				e -> assertThat(e).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class));
	}
}
