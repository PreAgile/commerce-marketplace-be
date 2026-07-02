package com.lemong.marketplace.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lemong.marketplace.TestcontainersConfiguration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 결제 불변식이 <b>DB 제약으로</b> 강제됨을 실 Postgres(Testcontainers)로 검증한다.
 *
 * <p>
 * 핵심: 검증을 애플리케이션 코드가 아니라 DB에 둔다 — 어떤 코드 경로(혹은 AI가 짠 우회 경로)로 들어와도 잘못된 데이터는
 * INSERT 자체가 거부된다. mock이 아니라 진짜 DB라야 이 검증이 유효하다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentConstraintsIT {

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE refund, payment RESTART IDENTITY CASCADE").update();
	}

	// 누적환불 상한·미결제 환불 제약은 M5-a에서 refund 원장(+트리거)으로 이전됐다 — RefundConstraintsIT 참조.
	private int insertPayment(String idempotencyKey, long paid, String status) {
		return jdbc.sql("""
				INSERT INTO payment (order_id, idempotency_key, paid_amount, status)
				VALUES (1, :key, :paid, :status)
				""").param("key", idempotencyKey).param("paid", paid).param("status", status).update();
	}

	@Test
	@DisplayName("정상 결제는 INSERT된다")
	void validPaymentInserts() {
		assertThat(insertPayment("idem-1", 10_000, "PAID")).isEqualTo(1);
	}

	@Test
	@DisplayName("음수 결제금액은 CHECK 제약으로 거부된다")
	void negativeAmountRejected() {
		assertThatThrownBy(() -> insertPayment("idem-2", -1, "PAID"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("정의되지 않은 결제 상태는 CHECK 제약으로 거부된다")
	void invalidStatusRejected() {
		assertThatThrownBy(() -> insertPayment("idem-5", 10_000, "INVALID"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("동시 따닥: 같은 멱등키로 16스레드가 동시 INSERT해도 단 1건만 성공한다")
	void concurrentDuplicateIdempotencyOnlyOneSucceeds() throws InterruptedException {
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicInteger success = new AtomicInteger();
		AtomicInteger conflicts = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(threads);

		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				try {
					start.await(); // 모든 스레드 동시 출발
					insertPayment("idem-race", 10_000, "PAID");
					success.incrementAndGet();
				} catch (DataIntegrityViolationException e) {
					conflicts.incrementAndGet(); // UNIQUE 충돌 = 기대된 실패
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		done.await();
		pool.shutdown();

		// DB UNIQUE 제약이 진짜 따닥 경합에서도 정확히 1건만 통과시킨다
		assertThat(success.get()).isEqualTo(1);
		assertThat(conflicts.get()).isEqualTo(threads - 1);
	}
}
