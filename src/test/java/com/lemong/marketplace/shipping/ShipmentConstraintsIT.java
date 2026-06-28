package com.lemong.marketplace.shipping;

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
 * 배송 불변식이 <b>DB 제약으로</b> 강제됨을 실 Postgres(Testcontainers)로 검증한다.
 *
 * <p>
 * 배송 슬라이스가 처음 도입하는 두 패턴을 확인한다:
 * <ul>
 * <li><b>이벤트 멱등 생성</b> — {@code (source_event_id, seller_id)} UNIQUE. 같은 결제확정
 * 이벤트를 재수신/따닥해도 셀러당 배송은 단 1건만 생긴다.
 * <li><b>append-only 상태 이력</b> — {@code most_recent} 부분 UNIQUE 인덱스가 "현재 상태는 단
 * 하나"를 강제.
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ShipmentConstraintsIT {

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		// shipment_event → shipment 순서로 비운다(FK).
		jdbc.sql("TRUNCATE TABLE shipment_event, shipment RESTART IDENTITY").update();
	}

	private int insertShipment(String sourceEventId, long sellerId, String status) {
		return jdbc.sql("""
				INSERT INTO shipment (order_id, seller_id, source_event_id, status)
				VALUES (1, :seller, :evid, :status)
				""").param("seller", sellerId).param("evid", sourceEventId).param("status", status).update();
	}

	private long insertShipmentReturningId(String sourceEventId, long sellerId) {
		return jdbc.sql("""
				INSERT INTO shipment (order_id, seller_id, source_event_id, status)
				VALUES (1, :seller, :evid, 'READY') RETURNING id
				""").param("seller", sellerId).param("evid", sourceEventId).query(Long.class).single();
	}

	private int insertEvent(long shipmentId, String from, String to, boolean mostRecent, int sortKey) {
		return jdbc.sql("""
				INSERT INTO shipment_event
				    (shipment_id, from_status, to_status, most_recent, sort_key, occurred_at)
				VALUES (:sid, :from, :to, :mr, :sk, now())
				""").param("sid", shipmentId).param("from", from).param("to", to).param("mr", mostRecent)
				.param("sk", sortKey).update();
	}

	@Test
	@DisplayName("정상 배송은 INSERT된다")
	void validShipmentInserts() {
		assertThat(insertShipment("evt-1", 10, "READY")).isEqualTo(1);
	}

	@Test
	@DisplayName("멀티셀러: 같은 결제확정 이벤트라도 셀러가 다르면 배송이 각각 생성된다")
	void sameEventDifferentSellersBothCreated() {
		assertThat(insertShipment("evt-multi", 10, "READY")).isEqualTo(1); // 셀러 A
		assertThat(insertShipment("evt-multi", 20, "READY")).isEqualTo(1); // 셀러 B
		Long count = jdbc.sql("SELECT count(*) FROM shipment WHERE source_event_id = 'evt-multi'").query(Long.class)
				.single();
		assertThat(count).isEqualTo(2L);
	}

	@Test
	@DisplayName("멱등: 같은 (이벤트, 셀러)로 두 번 생성하면 UNIQUE로 거부된다")
	void duplicateEventSellerRejected() {
		assertThat(insertShipment("evt-dup", 10, "READY")).isEqualTo(1);
		assertThatThrownBy(() -> insertShipment("evt-dup", 10, "READY"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("정의되지 않은 배송 상태는 CHECK 제약으로 거부된다")
	void invalidStatusRejected() {
		assertThatThrownBy(() -> insertShipment("evt-bad", 10, "TELEPORTED"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("동시 따닥: 같은 (이벤트, 셀러)로 16스레드가 동시 생성해도 단 1건만 성공한다")
	void concurrentDuplicateOnlyOneSucceeds() throws InterruptedException {
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicInteger success = new AtomicInteger();
		AtomicInteger conflicts = new AtomicInteger();

		// Java 21: ExecutorService는 AutoCloseable — try-with-resources가 예외 경로에서도 풀을
		// 닫는다.
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					try {
						start.await();
						insertShipment("evt-race", 10, "READY");
						success.incrementAndGet();
					} catch (DataIntegrityViolationException e) {
						conflicts.incrementAndGet(); // UNIQUE 충돌 = 기대된 실패(멱등)
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
		assertThat(conflicts.get()).isEqualTo(threads - 1);
	}

	@Test
	@DisplayName("append-only: 한 배송에 most_recent 행이 둘이면 부분 UNIQUE로 거부된다")
	void twoMostRecentRejected() {
		long shipmentId = insertShipmentReturningId("evt-hist", 10);
		assertThat(insertEvent(shipmentId, null, "READY", true, 0)).isEqualTo(1);
		// 직전 most_recent를 안 내리고 새 most_recent를 또 넣으면 "현재 상태가 둘" → 거부
		assertThatThrownBy(() -> insertEvent(shipmentId, "READY", "PICKED_UP", true, 1))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("append-only 정상 전이: 직전 most_recent를 내리고 새 행을 올리면 통과한다")
	void properTransitionFlipsMostRecent() {
		long shipmentId = insertShipmentReturningId("evt-ok", 10);
		insertEvent(shipmentId, null, "READY", true, 0);
		jdbc.sql("UPDATE shipment_event SET most_recent = FALSE WHERE shipment_id = :sid AND sort_key = 0")
				.param("sid", shipmentId).update();
		assertThat(insertEvent(shipmentId, "READY", "PICKED_UP", true, 1)).isEqualTo(1);

		String current = jdbc.sql("SELECT to_status FROM shipment_event WHERE shipment_id = :sid AND most_recent")
				.param("sid", shipmentId).query(String.class).single();
		assertThat(current).isEqualTo("PICKED_UP"); // 현재 상태 단 하나
	}

	@Test
	@DisplayName("같은 배송에 sort_key가 중복되면 거부된다")
	void duplicateSortKeyRejected() {
		long shipmentId = insertShipmentReturningId("evt-sk", 10);
		insertEvent(shipmentId, null, "READY", false, 0);
		assertThatThrownBy(() -> insertEvent(shipmentId, "READY", "PICKED_UP", false, 0))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("존재하지 않는 배송에 이벤트를 달면 FK로 거부된다")
	void eventForMissingShipmentRejected() {
		assertThatThrownBy(() -> insertEvent(999_999L, null, "READY", true, 0))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
