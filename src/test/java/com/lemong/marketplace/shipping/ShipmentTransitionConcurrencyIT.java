package com.lemong.marketplace.shipping;

import static org.assertj.core.api.Assertions.assertThat;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.shipping.application.ShipmentService;
import com.lemong.marketplace.shipping.domain.ShipmentStatus;
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
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 따닥 동시 전이: 같은 배송·같은 목표 상태로 N스레드가 동시에 전이를 시도해도 단 1건만 성공해야 한다. shipment 행
 * {@code FOR UPDATE} 직렬화로 패자는 갱신된 현재 상태(PICKED_UP)를 다시 읽고 자가 전이로 거부된다 —
 * most_recent 부분 UNIQUE에 의존하지 않고도 lost update가 없음을 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ShipmentTransitionConcurrencyIT {

	@Autowired
	ShipmentService shipments;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE shipment_event, shipment RESTART IDENTITY").update();
	}

	private long seedReadyShipment() {
		long id = jdbc.sql("""
				INSERT INTO shipment (order_id, seller_id, source_event_id, status)
				VALUES (1, 10, 'evt-race', 'READY') RETURNING id
				""").query(Long.class).single();
		jdbc.sql("""
				INSERT INTO shipment_event (shipment_id, from_status, to_status, most_recent, sort_key, occurred_at)
				VALUES (:id, NULL, 'READY', TRUE, 0, now())
				""").param("id", id).update();
		return id;
	}

	@Test
	@DisplayName("16스레드가 같은 전이를 동시에 시도해도 단 1건만 성공한다")
	void concurrentSameTransitionOnlyOneSucceeds() throws InterruptedException {
		long id = seedReadyShipment();
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicInteger success = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();

		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					try {
						start.await();
						shipments.recordTransition(id, ShipmentStatus.PICKED_UP, null);
						success.incrementAndGet();
					} catch (IllegalStateException e) {
						rejected.incrementAndGet(); // 갱신된 상태(PICKED_UP)에서 자가 전이 → 기대된 거부
					} catch (org.springframework.dao.DataIntegrityViolationException e) {
						rejected.incrementAndGet(); // 최후 보루(most_recent 부분 UNIQUE)도 거부로 인정
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			done.await();
		}

		assertThat(success.get()).isEqualTo(1);
		assertThat(rejected.get()).isEqualTo(threads - 1);

		// 단 한 번의 전이만 기록됐다: 현재 상태 1건 + 이력 2건(READY, PICKED_UP)
		Long mostRecent = jdbc.sql("SELECT count(*) FROM shipment_event WHERE shipment_id = :id AND most_recent")
				.param("id", id).query(Long.class).single();
		assertThat(mostRecent).isEqualTo(1L);
		Long total = jdbc.sql("SELECT count(*) FROM shipment_event WHERE shipment_id = :id").param("id", id)
				.query(Long.class).single();
		assertThat(total).isEqualTo(2L);
	}
}
