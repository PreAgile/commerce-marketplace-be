package com.lemong.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lemong.marketplace.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 불변식이 <b>DB 제약으로</b> 강제됨을 실 Postgres(Testcontainers)로 검증한다.
 *
 * <p>
 * 두 종류의 불변식을 확인한다:
 * <ul>
 * <li><b>단일 행 CHECK</b> — 수량&gt;0, 단가 음수,
 * {@code line_amount = unit_price×quantity}. INSERT 시점에 즉시 거부된다.
 * <li><b>교차 행 트리거</b> — "주문 총액 == Σ라인 금액". 여러 라인에 걸친 합이라 단일 CHECK로 못 막고
 * DEFERRABLE 제약 트리거가 <i>커밋 시점</i>에 검사한다.
 * </ul>
 *
 * <p>
 * 지연 제약은 커밋에서만 검사되므로 테스트는 헤더+라인을 <b>한 트랜잭션</b>으로 넣고 끝에서
 * {@code SET CONSTRAINTS ALL IMMEDIATE}로 검사를 그 자리에서 강제한다 — 그래야 위반이 (커밋 시점의
 * TransactionSystemException이 아니라) 결제 테스트와 동일한
 * DataIntegrityViolationException으로 잡힌다. @Transactional 롤백을 쓰면 커밋이 없어 지연 제약이
 * <i>아예 검사되지 않으므로</i> 일부러 쓰지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OrderConstraintsIT {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PlatformTransactionManager txManager;

	TransactionTemplate tx;

	@BeforeEach
	void clean() {
		tx = new TransactionTemplate(txManager);
		// order_line → orders 순서로 비운다(FK). CASCADE 없이 명시적.
		jdbc.sql("TRUNCATE TABLE order_line, orders RESTART IDENTITY").update();
	}

	/** 헤더 1건 + 라인 N건을 한 TX로 INSERT하고, 지연 제약을 그 자리에서 검사한다. */
	private long createOrder(long total, long[][] lines) {
		return tx.execute(status -> {
			Long orderId = jdbc.sql("""
					INSERT INTO orders (buyer_id, total_amount, status)
					VALUES (1, :total, 'CREATED') RETURNING id
					""").param("total", total).query(Long.class).single();
			for (long[] line : lines) { // {seller_id, quantity, unit_price}
				long qty = line[1];
				long price = line[2];
				jdbc.sql("""
						INSERT INTO order_line
						    (order_id, seller_id, product_id, quantity, unit_price, line_amount)
						VALUES (:oid, :seller, 1, :qty, :price, :amount)
						""").param("oid", orderId).param("seller", line[0]).param("qty", qty).param("price", price)
						.param("amount", qty * price).update();
			}
			// 지연 제약(총액=Σ라인)을 지금 검사 → 위반 시 DataIntegrityViolationException
			jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
			return orderId;
		});
	}

	@Test
	@DisplayName("멀티셀러 정상 주문(총액=Σ라인)은 INSERT된다")
	void validMultiSellerOrderInserts() {
		// 셀러 A(10): 2×3000=6000, 셀러 B(20): 1×4000=4000 → 총 10000
		long orderId = createOrder(10_000, new long[][]{{10, 2, 3_000}, {20, 1, 4_000}});

		assertThat(orderId).isPositive();
		Long sellers = jdbc.sql("SELECT count(DISTINCT seller_id) FROM order_line WHERE order_id = :id")
				.param("id", orderId).query(Long.class).single();
		assertThat(sellers).isEqualTo(2L); // 한 주문에 두 셀러 라인 공존
	}

	@Test
	@DisplayName("주문 총액이 Σ라인 금액과 다르면 제약 트리거가 거부한다")
	void headerTotalMismatchRejected() {
		// 라인 합은 10000인데 헤더는 9999
		assertThatThrownBy(() -> createOrder(9_999, new long[][]{{10, 2, 3_000}, {20, 1, 4_000}}))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("라인 없는 주문에 총액이 있으면(>0) 제약 트리거가 거부한다")
	void headerWithoutLinesRejected() {
		assertThatThrownBy(() -> createOrder(5_000, new long[][]{}))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("수량 0 라인은 CHECK 제약으로 거부된다")
	void zeroQuantityRejected() {
		assertThatThrownBy(() -> createOrder(0, new long[][]{{10, 0, 3_000}}))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("음수 단가는 라인 CHECK(ck_oline_price_nonneg)로 거부된다")
	void negativeUnitPriceRejected() {
		// 헤더 총액은 0(>=0, 합법)으로 두고 라인 단가만 음수 → 음수 단가 CHECK가 *먼저* 발동.
		// (헤더까지 음수로 두면 ck_orders_total_nonneg가 먼저 걸려 엉뚱한 제약을 검증하게 됨)
		assertThatThrownBy(() -> createOrder(0, new long[][]{{10, 1, -3_000}}))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("라인의 order_id를 다른 주문으로 옮기는 UPDATE는 거부된다(reparent 금지)")
	void reparentingLineRejected() {
		long orderA = createOrder(6_000, new long[][]{{10, 2, 3_000}});
		long orderB = createOrder(4_000, new long[][]{{20, 1, 4_000}});
		Long lineOfA = jdbc.sql("SELECT id FROM order_line WHERE order_id = :a").param("a", orderA).query(Long.class)
				.single();

		// A의 라인을 B로 이동 시도 → BEFORE 트리거가 즉시 거부(옛 주문 A의 합이 틀어지는 write skew 봉쇄)
		assertThatThrownBy(() -> jdbc.sql("UPDATE order_line SET order_id = :b WHERE id = :id").param("b", orderB)
				.param("id", lineOfA).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("line_amount ≠ unit_price×quantity 면 CHECK 제약으로 거부된다")
	void tamperedLineAmountRejected() {
		assertThatThrownBy(() -> tx.execute(status -> {
			Long orderId = jdbc
					.sql("INSERT INTO orders (buyer_id, total_amount, status) VALUES (1, 7777, 'CREATED') RETURNING id")
					.query(Long.class).single();
			// 2×3000=6000 이어야 하는데 line_amount를 7777로 위조
			jdbc.sql("""
					INSERT INTO order_line
					    (order_id, seller_id, product_id, quantity, unit_price, line_amount)
					VALUES (:oid, 10, 1, 2, 3000, 7777)
					""").param("oid", orderId).update();
			return orderId;
		})).isInstanceOf(DataIntegrityViolationException.class);
	}
}
