package com.lemong.marketplace.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lemong.marketplace.TestcontainersConfiguration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 정산 원장({@code settlement_line})의 불변식이 DB 제약으로 강제됨을 실 Postgres로 검증한다.
 *
 * <ul>
 *   <li><b>부호 정합</b> — {@code ck_settline_sign}: entry_type별 amount 부호가 어긋나거나 0이면 거부.
 *   <li><b>멱등</b> — {@code source_event_id} UNIQUE: at-least-once 이벤트 중복 적재 차단.
 *   <li><b>출처당 1줄</b> — {@code uq_settline_sale}: 같은 (source_type, source_id)에 SALE 두 줄 차단.
 *   <li><b>경계 FK</b> — cycle_id는 같은 BC(settlement_cycle)라 FK.
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SettlementLineConstraintsIT {

    @Autowired
    JdbcClient jdbc;

    private final AtomicLong seq = new AtomicLong();

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE TABLE settlement_line, settlement_cycle RESTART IDENTITY").update();
    }

    /** 부호·타입 검증용. source_id/event_id는 고유 발급해 다른 제약(uq)과 충돌하지 않게 한다. */
    private int insertLine(String entryType, long amount) {
        long n = seq.incrementAndGet();
        return insertLine(entryType, amount, "ORDER_ITEM", n, "evt-" + n, null);
    }

    private int insertLine(String entryType, long amount, String sourceType, long sourceId, String eventId,
            Long cycleId) {
        return jdbc.sql("""
                        INSERT INTO settlement_line
                            (seller_id, entry_type, source_type, source_id, source_event_id,
                             amount_minor, eligible_at, cycle_id)
                        VALUES (10, :type, :stype, :sid, :evid, :amt, now(), :cycle)
                        """)
                .param("type", entryType)
                .param("stype", sourceType)
                .param("sid", sourceId)
                .param("evid", eventId)
                .param("amt", amount)
                .param("cycle", cycleId)
                .update();
    }

    @Test
    @DisplayName("SALE은 양수면 통과, 음수면 부호 CHECK로 거부")
    void saleSignEnforced() {
        assertThat(insertLine("SALE", 10_000)).isEqualTo(1);
        assertThatThrownBy(() -> insertLine("SALE", -10_000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("COMMISSION은 음수면 통과, 양수면 부호 CHECK로 거부")
    void commissionSignEnforced() {
        assertThat(insertLine("COMMISSION", -1_500)).isEqualTo(1);
        assertThatThrownBy(() -> insertLine("COMMISSION", 1_500))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("REFUND는 음수만 허용된다")
    void refundMustBeNegative() {
        assertThat(insertLine("REFUND", -3_000)).isEqualTo(1);
        assertThatThrownBy(() -> insertLine("REFUND", 3_000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("ADJUSTMENT는 0이면 부호 CHECK로 거부된다(0원 정산 라인 차단)")
    void adjustmentZeroRejected() {
        assertThat(insertLine("ADJUSTMENT", -500)).isEqualTo(1);
        assertThat(insertLine("ADJUSTMENT", 500)).isEqualTo(1);
        assertThatThrownBy(() -> insertLine("ADJUSTMENT", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("정의되지 않은 entry_type은 CHECK 제약으로 거부된다")
    void invalidEntryTypeRejected() {
        assertThatThrownBy(() -> insertLine("BONUS", 1_000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("멱등: 같은 source_event_id 재적재는 UNIQUE로 거부된다")
    void duplicateEventRejected() {
        insertLine("SALE", 10_000, "ORDER_ITEM", 1, "evt-dup", null);
        assertThatThrownBy(() -> insertLine("SALE", 10_000, "ORDER_ITEM", 2, "evt-dup", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 출처(source_type, source_id)에 SALE 두 줄은 부분 UNIQUE로 거부된다")
    void duplicateSaleForSameSourceRejected() {
        insertLine("SALE", 10_000, "ORDER_ITEM", 100, "evt-s1", null);
        // 다른 이벤트키지만 같은 출처의 SALE → uq_settline_sale 위반
        assertThatThrownBy(() -> insertLine("SALE", 9_000, "ORDER_ITEM", 100, "evt-s2", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 출처라도 SALE과 COMMISSION은 각각 한 줄씩 공존한다")
    void saleAndCommissionCoexistForSameSource() {
        assertThat(insertLine("SALE", 10_000, "ORDER_ITEM", 200, "evt-sale", null)).isEqualTo(1);
        assertThat(insertLine("COMMISSION", -1_000, "ORDER_ITEM", 200, "evt-comm", null)).isEqualTo(1);
    }

    @Test
    @DisplayName("존재하지 않는 cycle_id에 귀속하면 FK로 거부된다")
    void missingCycleRejected() {
        assertThatThrownBy(() -> insertLine("SALE", 10_000, "ORDER_ITEM", 300, "evt-fk", 999_999L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
