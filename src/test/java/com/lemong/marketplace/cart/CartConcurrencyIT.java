package com.lemong.marketplace.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.cart.application.CartService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 장바구니 동시성 — 같은 카트로의 동시 "담기"가 수량 유실(lost update) 없이 정합하게 수렴함을 실 Postgres로 검증한다.
 *
 * <p>
 * 핵심 race: {@code addItem}은 (읽기 → 수량 누적 → 쓰기)의 read-modify-write다. 두 트랜잭션이 같은
 * 값을 읽으면 한쪽 증분이 사라진다. 애그리거트 루트(Cart)의 낙관적 락(@Version +
 * OPTIMISTIC_FORCE_INCREMENT)이 동시 쓰기를 cart 행에서 직렬화하고, 진 쪽은 충돌 예외를 받아 재시도한다.
 *
 * <p>
 * {@link #runConcurrently}는 CountDownLatch로 N스레드를 같은 순간에 출발시켜 실제 경합을 재현한다. 각
 * 스레드는 클라이언트 재시도를 흉내내 충돌 예외 시 다시 시도한다 — 낙관적 락이 없으면 충돌이 애초에 발생하지 않으므로 재시도도 없고,
 * lost update가 그대로 남아 최종 수량이 기대보다 작아진다(빨강).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CartConcurrencyIT {

	private static final int THREADS = 16;

	@Autowired
	CartService cartService;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE cart_item, cart RESTART IDENTITY CASCADE").update();
	}

	@Test
	@DisplayName("이미 담긴 상품을 N스레드가 동시에 더 담아도 수량 누적이 유실되지 않는다")
	void concurrentAccumulationDoesNotLoseUpdates() throws Exception {
		long cartId = cartService.createCart(1L);
		cartService.addItem(cartId, 100L, 10L, 3_000L, 1); // 시드: quantity = 1

		runConcurrently(() -> addWithRetry(cartId, 100L, 10L, 3_000L, 1));

		int finalQty = quantityOf(cartId, 100L);
		assertThat(finalQty).isEqualTo(1 + THREADS);
	}

	@Test
	@DisplayName("새 상품을 N스레드가 동시에 담아도 한 줄로 수렴하고 수량이 정확하다")
	void concurrentFirstAddConvergesToSingleLine() throws Exception {
		long cartId = cartService.createCart(1L);

		runConcurrently(() -> addWithRetry(cartId, 200L, 20L, 4_000L, 1));

		assertThat(lineCountOf(cartId)).isEqualTo(1);
		assertThat(quantityOf(cartId, 200L)).isEqualTo(THREADS);
	}

	/** 동시 쓰기 충돌(낙관적 락 / 중복 INSERT)은 클라이언트 재시도로 흡수한다. */
	private void addWithRetry(long cartId, long productId, long sellerId, long unitPrice, int qty) {
		while (true) {
			try {
				cartService.addItem(cartId, productId, sellerId, unitPrice, qty);
				return;
			} catch (OptimisticLockingFailureException | DataIntegrityViolationException retryable) {
				// 충돌 → 최신 상태를 다시 읽고 재시도
			}
		}
	}

	private int quantityOf(long cartId, long productId) {
		return jdbc.sql("SELECT quantity FROM cart_item WHERE cart_id = :c AND product_id = :p").param("c", cartId)
				.param("p", productId).query(Integer.class).single();
	}

	private int lineCountOf(long cartId) {
		return jdbc.sql("SELECT count(*) FROM cart_item WHERE cart_id = :c").param("c", cartId).query(Integer.class)
				.single();
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
			ready.await(); // 모든 스레드가 출발선에 설 때까지
			start.countDown(); // 동시에 출발
			assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdownNow();
		}
	}
}
