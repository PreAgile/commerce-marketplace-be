package com.lemong.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.cart.application.CartService;
import com.lemong.marketplace.order.application.PlaceOrderService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 같은 카트를 동시에 이중 주문해도 정확히 한 건만 생성됨을 실 Postgres로 검증한다.
 *
 * <p>
 * 카트 닫기(markOrdered)가 cart의 낙관적 락(@Version + FORCE_INCREMENT)을 타므로 동시 전환은 cart
 * 행에서 직렬화된다 — 한 트랜잭션만 커밋되고 나머지는 충돌로 롤백된다(주문 행도 함께 롤백). 멱등 재시도 없이 "정확히 1건"을 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OrderPlacementConcurrencyIT {

	private static final int THREADS = 16;

	@Autowired
	CartService carts;

	@Autowired
	PlaceOrderService orders;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE order_line, orders, cart_item, cart RESTART IDENTITY CASCADE").update();
	}

	@Test
	@DisplayName("같은 카트를 N스레드가 동시에 주문하면 주문은 정확히 1건만 생성된다")
	void concurrentDoubleOrderCreatesExactlyOne() throws Exception {
		long cartId = carts.createCart(1L);
		carts.addItem(cartId, 100L, 10L, 3_000L, 2);

		AtomicInteger succeeded = new AtomicInteger();
		runConcurrently(() -> {
			try {
				orders.place(cartId);
				succeeded.incrementAndGet();
			} catch (RuntimeException expectedForLosers) {
				// 충돌/이미 ORDERED → 진 스레드는 실패한다
			}
		});

		assertThat(succeeded.get()).isEqualTo(1);
		Integer orderCount = jdbc.sql("SELECT count(*) FROM orders").query(Integer.class).single();
		assertThat(orderCount).isEqualTo(1);
	}

	private void runConcurrently(Runnable task) throws InterruptedException {
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
						task.run();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
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
	}
}
