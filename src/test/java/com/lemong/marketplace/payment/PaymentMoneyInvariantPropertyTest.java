package com.lemong.marketplace.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lemong.marketplace.TestcontainersConfiguration;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 돈 불변식을 property로 검증한다: DB는 "0 ≤ refunded ≤ paid" 일 때만 INSERT를 허용해야 한다.
 *
 * <p>예제 테스트(PaymentConstraintsIT)가 못 보는 입력 공간을 랜덤 수백 시나리오로 폭격(AGENTS.md).
 * jqwik @Property는 자체 엔진으로 돌기 때문에 Spring DI를 위해 @JqwikSpringSupport가 필요하다.
 */
@JqwikSpringSupport
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentMoneyInvariantPropertyTest {

    @Autowired
    JdbcClient jdbc;

    // 격리: 각 try가 고유 멱등키(seq)를 써 행이 충돌하지 않으므로 별도 cleanup이 불필요하다.
    // (jqwik @BeforeProperty로 TRUNCATE를 넣자 jqwik-spring 주입 타이밍 문제로 jdbc가 null → NPE.
    //  CI가 이를 잡아냈고, 고유키 격리로 되돌렸다.)
    private final AtomicLong seq = new AtomicLong();

    @Property(tries = 200)
    void dbAcceptsInsertIffMoneyInvariantHolds(
            @ForAll @LongRange(min = -1_000, max = 100_000) long paid,
            @ForAll @LongRange(min = -1_000, max = 100_000) long refunded) {

        boolean shouldAccept = paid >= 0 && refunded >= 0 && refunded <= paid;
        String key = "prop-" + seq.incrementAndGet();   // try마다 고유 멱등키

        if (shouldAccept) {
            assertThat(insert(key, paid, refunded)).isEqualTo(1);
        } else {
            assertThatThrownBy(() -> insert(key, paid, refunded))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private int insert(String key, long paid, long refunded) {
        return jdbc.sql("""
                        INSERT INTO payment (order_id, idempotency_key, paid_amount, refunded_amount, status)
                        VALUES (1, :key, :paid, :refunded, 'PAID')
                        """)
                .param("key", key)
                .param("paid", paid)
                .param("refunded", refunded)
                .update();
    }
}
