package com.lemong.marketplace.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lemong.marketplace.TestcontainersConfiguration;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 정산 원장의 부호 정합(ck_settline_sign)을 property로 검증한다: DB는 entry_type별 부호 규칙을 만족할 때만
 * 라인을 받아들여야 한다.
 *
 * <p>예제 테스트(SettlementLineConstraintsIT)가 못 보는 (타입 × 금액) 조합을 랜덤 수백 시나리오로 폭격한다.
 * 각 try가 고유 source_id/event_id를 써 멱등·출처 UNIQUE 제약과 충돌하지 않게 격리한다.
 */
@JqwikSpringSupport
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SettlementSignPropertyTest {

    private static final String[] TYPES = {
        "SALE", "COMMISSION", "REFUND", "REFUND_COMMISSION",
        "RETURN_SHIPPING_FEE", "PG_FEE_NONREFUND", "ADJUSTMENT", "REPAYMENT"
    };

    @Autowired
    JdbcClient jdbc;

    private final AtomicLong seq = new AtomicLong();

    @Property(tries = 300)
    void dbAcceptsLineIffSignRuleHolds(
            @ForAll @IntRange(min = 0, max = 7) int typeIdx,
            @ForAll @LongRange(min = -100_000, max = 100_000) long amount) {

        String entryType = TYPES[typeIdx];
        boolean shouldAccept = signRuleHolds(entryType, amount);

        if (shouldAccept) {
            assertThat(insert(entryType, amount)).isEqualTo(1);
        } else {
            assertThatThrownBy(() -> insert(entryType, amount))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    /** ck_settline_sign 과 동일한 규칙(테스트 오라클). */
    private boolean signRuleHolds(String entryType, long amount) {
        return switch (entryType) {
            case "SALE", "REFUND_COMMISSION", "REPAYMENT" -> amount > 0;
            case "COMMISSION", "REFUND", "RETURN_SHIPPING_FEE", "PG_FEE_NONREFUND" -> amount < 0;
            case "ADJUSTMENT" -> amount != 0;
            default -> false;
        };
    }

    private int insert(String entryType, long amount) {
        long n = seq.incrementAndGet();   // try마다 고유 출처/이벤트 → uq 충돌 회피
        return jdbc.sql("""
                        INSERT INTO settlement_line
                            (seller_id, entry_type, source_type, source_id, source_event_id,
                             amount_minor, eligible_at, cycle_id)
                        VALUES (10, :type, 'MANUAL', :sid, :evid, :amt, now(), NULL)
                        """)
                .param("type", entryType)
                .param("sid", n)
                .param("evid", "prop-" + n)
                .param("amt", amount)
                .update();
    }
}
