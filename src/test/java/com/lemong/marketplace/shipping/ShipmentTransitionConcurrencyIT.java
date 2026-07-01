package com.lemong.marketplace.shipping;

import static org.assertj.core.api.Assertions.assertThat;

import com.lemong.marketplace.TestcontainersConfiguration;
import com.lemong.marketplace.shipping.application.ShipmentService;
import com.lemong.marketplace.shipping.domain.ShipmentStatus;
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
		jdbc.sql("TRUNCATE TABLE outbox, shipment_event, shipment RESTART IDENTITY").update();
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

	// 시작 상태도 raw INSERT가 아니라 도메인 전이로 쌓는다: 실제 이력(sort_key 0..3)이 생기고 경로 자체가
	// 상태머신 화이트리스트로 검증돼, 전이 규칙 회귀를 픽스처 단계에서 잡는다(CR-1). 중간 전이는 이벤트를 발행하지
	// 않아 race 전 outbox가 비어 있다.
	private long seedOutForDelivery() {
		long id = seedReadyShipment();
		shipments.recordTransition(id, ShipmentStatus.PICKED_UP, null);
		shipments.recordTransition(id, ShipmentStatus.IN_TRANSIT, null);
		shipments.recordTransition(id, ShipmentStatus.OUT_FOR_DELIVERY, null);
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
		AtomicInteger dbFallback = new AtomicInteger();

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
						// FOR UPDATE 직렬화가 살아있으면 여기로 오지 않는다. 오면 잠금이 깨져 UNIQUE 최후보루로
						// 떨어진 것 = 회귀. 거부로 세지 않고 아래에서 0을 단언해 테스트를 빨갛게 만든다(PR #20 리뷰).
						dbFallback.incrementAndGet();
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
		assertThat(dbFallback.get())
				.as("FOR UPDATE 직렬화가 살아있으면 패자는 자가전이(IllegalStateException)로 거부되지 UNIQUE 최후보루로 떨어지지 않는다").isZero();
		assertThat(rejected.get()).isEqualTo(threads - 1);

		// 단 한 번의 전이만 기록됐다: 현재 상태 1건 + 이력 2건(READY, PICKED_UP)
		Long mostRecent = jdbc.sql("SELECT count(*) FROM shipment_event WHERE shipment_id = :id AND most_recent")
				.param("id", id).query(Long.class).single();
		assertThat(mostRecent).isEqualTo(1L);
		Long total = jdbc.sql("SELECT count(*) FROM shipment_event WHERE shipment_id = :id").param("id", id)
				.query(Long.class).single();
		assertThat(total).isEqualTo(2L);
	}

	@Test
	@DisplayName("16스레드가 동시에 DELIVERED로 전이해도 정산-가능 이벤트는 정확히 1건만 적재된다")
	void concurrentDeliveredPublishesExactlyOneEvent() throws InterruptedException {
		long id = seedOutForDelivery();
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicInteger success = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		AtomicInteger dbFallback = new AtomicInteger();
		AtomicReference<Throwable> unexpected = new AtomicReference<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					try {
						start.await();
						shipments.recordTransition(id, ShipmentStatus.DELIVERED, null);
						success.incrementAndGet();
					} catch (IllegalStateException e) {
						rejected.incrementAndGet(); // 갱신된 DELIVERED에서 자가 전이 → 기대된 거부
					} catch (org.springframework.dao.DataIntegrityViolationException e) {
						// 여기로 오면 직렬화가 깨져 outbox uq(최후보루)로 떨어진 것 = 이중 정산 위험 회귀
						dbFallback.incrementAndGet();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						unexpected.compareAndSet(null, e);
					} catch (Throwable e) {
						unexpected.compareAndSet(null, e); // 예상 밖 예외로 조용히 죽는 패자를 잡는다(CR-2)
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			done.await();
		}

		assertThat(success.get()).isEqualTo(1);
		assertThat(unexpected.get()).as("패자는 예상된 자가전이 거부 외의 이유로 죽으면 안 된다").isNull();
		assertThat(rejected.get()).isEqualTo(threads - 1);
		assertThat(dbFallback.get()).as("정산-가능 이벤트는 핫로우 락으로 정확히 1회 발행되어야 한다(uq 최후보루에 의존 금지)").isZero();
		// 이중 정산 방지의 핵심 불변식: DELIVERED 이벤트는 배송당 정확히 1건.
		Long events = jdbc.sql("""
				SELECT count(*) FROM outbox
				WHERE aggregate_type = 'shipment' AND aggregate_id = :id AND event_type = 'ShipmentDelivered'
				""").param("id", id).query(Long.class).single();
		assertThat(events).isEqualTo(1L);
	}
}
