package com.lemong.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lemong.marketplace.TestcontainersConfiguration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 보존 불변식을 property로 검증한다: DB는 <b>헤더 total_amount == Σ(라인 line_amount)</b> 일 때만
 * 주문을 받아들여야 한다.
 *
 * <p>예제 테스트(OrderConstraintsIT)가 못 보는 입력 공간(라인 수·수량·단가의 임의 조합)을
 * 랜덤 수백 시나리오로 폭격한다(AGENTS.md). 각 라인은 서로 다른 seller_id를 써 멀티셀러 구성을 재현한다.
 *
 * <p>지연 제약은 커밋에서만 검사되므로 헤더+라인을 한 TX로 넣고 {@code SET CONSTRAINTS ALL IMMEDIATE}로
 * 그 자리에서 검사를 강제한다(OrderConstraintsIT와 동일 이유).
 */
@JqwikSpringSupport
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OrderTotalInvariantPropertyTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    // 각 try가 고유 주문을 새로 만들어 서로 격리되므로 별도 cleanup이 불필요하다.
    private final AtomicLong seq = new AtomicLong();

    @Property(tries = 200)
    void dbAcceptsOrderIffHeaderTotalEqualsLineSum(
            @ForAll @Size(min = 1, max = 5) List<@IntRange(min = 1, max = 100) Integer> quantities,
            @ForAll @LongRange(min = 0, max = 100_000) long unitPrice,
            @ForAll boolean tamper,
            @ForAll @LongRange(min = 1, max = 50_000) long tamperAmount) {

        long trueSum = quantities.stream().mapToLong(q -> q * unitPrice).sum();
        // tamper가 true면 헤더 총액을 일부러 틀리게(차이 ≥ 1) 만든다 → 거부되어야 함
        long headerTotal = tamper ? trueSum + tamperAmount : trueSum;
        boolean shouldAccept = !tamper;

        TransactionTemplate tx = new TransactionTemplate(txManager);
        long buyer = seq.incrementAndGet();   // try마다 고유 → 행 격리

        if (shouldAccept) {
            assertThat(insertOrder(tx, buyer, headerTotal, quantities, unitPrice)).isPositive();
        } else {
            assertThatThrownBy(() -> insertOrder(tx, buyer, headerTotal, quantities, unitPrice))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private long insertOrder(
            TransactionTemplate tx, long buyer, long headerTotal, List<Integer> quantities, long unitPrice) {
        return tx.execute(status -> {
            Long orderId = jdbc.sql("""
                            INSERT INTO orders (buyer_id, total_amount, status)
                            VALUES (:buyer, :total, 'CREATED') RETURNING id
                            """)
                    .param("buyer", buyer)
                    .param("total", headerTotal)
                    .query(Long.class)
                    .single();
            long sellerId = 0;
            for (int qty : quantities) {
                jdbc.sql("""
                                INSERT INTO order_line
                                    (order_id, seller_id, product_id, quantity, unit_price, line_amount)
                                VALUES (:oid, :seller, 1, :qty, :price, :amount)
                                """)
                        .param("oid", orderId)
                        .param("seller", ++sellerId)     // 라인마다 다른 셀러
                        .param("qty", qty)
                        .param("price", unitPrice)
                        .param("amount", qty * unitPrice)
                        .update();
            }
            jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
            return orderId;
        });
    }
}
