package com.lemong.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.cart.application.CartService;
import com.lemong.marketplace.order.application.PlaceOrderService;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 주문 생성의 동시성 — 카트 닫기가 cart의 낙관적 락(@Version + FORCE_INCREMENT)을 타 한 카트로의 동시 작업이
 * cart 행에서 직렬화됨을 실 Postgres로 검증한다. consumeForOrder가 읽기+닫기를 한 락 로드로 묶으므로 (1) 동시
 * 이중 주문은 정확히 1건, (2) 주문과 카트 수정이 겹쳐도 주문 라인은 닫힌 카트 내용과 일치한다(stale 주문 없음).
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
	@DisplayName("같은 카트를 N스레드가 동시에 주문하면 주문은 정확히 1건만 생성되고, 패배 스레드는 경합 예외로만 실패한다")
	void concurrentDoubleOrderCreatesExactlyOne() throws Exception {
		long cartId = carts.createCart(1L);
		carts.addItem(cartId, 100L, 10L, 3_000L, 2);

		AtomicInteger succeeded = new AtomicInteger();
		ConcurrentLinkedQueue<Throwable> losers = new ConcurrentLinkedQueue<>();
		runConcurrently(THREADS, () -> {
			try {
				orders.place(cartId);
				succeeded.incrementAndGet();
			} catch (RuntimeException e) {
				losers.add(e); // 삼키지 않고 모은다 — 뒤에서 "예상 경합 예외인지" 검증
			}
		});

		assertThat(succeeded.get()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM orders").query(Integer.class).single()).isEqualTo(1);
		// 패배 예외는 전부 예상된 경합 경로여야 한다(낙관적 충돌 또는 이미 ORDERED) — NPE·매핑오류 같은 무관한 회귀를 숨기지 않는다.
		assertThat(losers).hasSize(THREADS - 1);
		assertThat(losers).allSatisfy(e -> assertThat(e).isInstanceOfAny(OptimisticLockingFailureException.class,
				IllegalStateException.class));
	}

	@Test
	@DisplayName("주문과 카트 수정이 동시에 일어나도 생성된 주문의 라인은 닫힌 카트 항목과 정확히 일치한다(stale 주문 없음)")
	void concurrentPlaceAndCartEditStayConsistent() throws Exception {
		// 읽기→닫기 사이에 항목이 추가되는 race를 여러 번 시도해 폭격한다. 원자 consumeForOrder가 없으면 주문이
		// 옛 스냅샷(1줄)으로 만들어지고 카트는 2줄로 닫혀 불일치가 난다.
		for (int round = 0; round < 30; round++) {
			jdbc.sql("TRUNCATE TABLE order_line, orders, cart_item, cart RESTART IDENTITY CASCADE").update();
			long cartId = carts.createCart(1L);
			carts.addItem(cartId, 100L, 10L, 1_000L, 1);

			AtomicReference<Long> placedOrderId = new AtomicReference<>();
			runConcurrently(2, taskByIndex -> {
				if (taskByIndex == 0) {
					try {
						placedOrderId.set(orders.place(cartId));
					} catch (RuntimeException ignoredLoser) {
						// 경합 패배 — 주문 미생성
					}
				} else {
					try {
						carts.addItem(cartId, 200L, 20L, 2_000L, 1);
					} catch (RuntimeException ignoredLoser) {
						// 카트가 이미 닫혔거나 충돌 — 항목 미추가
					}
				}
			});

			Long orderId = placedOrderId.get();
			if (orderId == null) {
				continue; // 주문이 안 만들어진 라운드는 검증할 stale 주문이 없음
			}
			// 핵심 불변식: 주문이 생겼다면 카트는 닫혔고, 주문 라인 집합 == 카트 항목 집합이어야 한다.
			assertThat(
					jdbc.sql("SELECT status FROM cart WHERE id = :c").param("c", cartId).query(String.class).single())
					.isEqualTo("ORDERED");
			List<String> orderLines = jdbc.sql(
					"SELECT product_id, seller_id, quantity FROM order_line WHERE order_id = :o ORDER BY product_id")
					.param("o", orderId).query((rs, n) -> rs.getLong("product_id") + ":" + rs.getLong("seller_id") + ":"
							+ rs.getInt("quantity"))
					.list();
			List<String> cartItems = jdbc
					.sql("SELECT product_id, seller_id, quantity FROM cart_item WHERE cart_id = :c ORDER BY product_id")
					.param("c", cartId).query((rs, n) -> rs.getLong("product_id") + ":" + rs.getLong("seller_id") + ":"
							+ rs.getInt("quantity"))
					.list();
			assertThat(orderLines).isEqualTo(cartItems);
		}
	}

	private void runConcurrently(int threads, java.util.function.IntConsumer taskByIndex) throws InterruptedException {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		try {
			for (int i = 0; i < threads; i++) {
				int index = i;
				pool.submit(() -> {
					ready.countDown();
					try {
						start.await();
						taskByIndex.accept(index);
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

	private void runConcurrently(int threads, Runnable task) throws InterruptedException {
		runConcurrently(threads, index -> task.run());
	}
}
