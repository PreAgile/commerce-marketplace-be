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

// 회귀: 동시 선점에서 JPA save→23505가 Hibernate 세션을 오염시켜 멱등이 깨지던 버그(ON CONFLICT로 수정).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentInitiateConcurrencyIT {

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
	@DisplayName("같은 멱등키로 N스레드가 동시에 선점해도 payment 1건·모두 같은 id를 받는다")
	void concurrentInitiateIsIdempotent() throws Exception {
		AtomicInteger ok = new AtomicInteger();
		ConcurrentLinkedQueue<Long> ids = new ConcurrentLinkedQueue<>();
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
						ids.add(payments.initiate(1L, "same-key", 5_000L));
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

		assertThat(jdbc.sql("SELECT count(*) FROM payment").query(Integer.class).single()).isEqualTo(1);
		assertThat(ok.get()).isEqualTo(THREADS);
		assertThat(losers).isEmpty();
		assertThat(ids).allSatisfy(id -> assertThat(id).isEqualTo(ids.peek()));
	}
}
