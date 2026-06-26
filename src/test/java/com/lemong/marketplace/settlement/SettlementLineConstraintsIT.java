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
 *   <li><b>멱등(복합키)</b> — {@code (source_event_id, entry_type, seller_id)} UNIQUE: 진짜 중복은 막되
 *       한 사건의 fan-out(SALE/COMMISSION 분기, 멀티셀러 분기)은 허용.
 *   <li><b>출처당 1줄</b> — {@code uq_settline_sale}/{@code uq_settline_comm}: 같은 출처에 SALE/COMMISSION 두 줄 차단.
 *   <li><b>타입 도메인</b> — {@code ck_settline_type}/{@code ck_settline_source_type}.
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
        return insertLine(10, entryType, amount, "ORDER_ITEM", n, "evt-" + n, null);
    }

    private int insertLine(String entryType, long amount, String sourceType, long sourceId, String eventId,
            Long cycleId) {
        return insertLine(10, entryType, amount, sourceType, sourceId, eventId, cycleId);
    }

    private int insertLine(long sellerId, String entryType, long amount, String sourceType, long sourceId,
            String eventId, Long cycleId) {
        return jdbc.sql("""
                        INSERT INTO settlement_line
                            (seller_id, entry_type, source_type, source_id, source_event_id,
                             amount_minor, eligible_at, cycle_id)
                        VALUES (:seller, :type, :stype, :sid, :evid, :amt, now(), :cycle)
                        """)
                .param("seller", sellerId)
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
    @DisplayName("0원 라인은 어떤 entry_type이든 부호 CHECK로 거부된다(0 경계)")
    void zeroAmountRejectedForEveryType() {
        String[] types = {
            "SALE", "COMMISSION", "REFUND", "REFUND_COMMISSION",
            "RETURN_SHIPPING_FEE", "PG_FEE_NONREFUND", "ADJUSTMENT", "REPAYMENT"
        };
        for (String type : types) {
            assertThatThrownBy(() -> insertLine(type, 0))
                    .as("entry_type=%s, amount=0", type)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    @DisplayName("±1 경계: 양수타입은 +1 통과/−1 거부, 음수타입은 −1 통과/+1 거부")
    void plusMinusOneBoundary() {
        assertThat(insertLine("SALE", 1)).isEqualTo(1);            // 양수타입 +1 통과
        assertThatThrownBy(() -> insertLine("SALE", -1))           // 양수타입 −1 거부
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(insertLine("COMMISSION", -1)).isEqualTo(1);     // 음수타입 −1 통과
        assertThatThrownBy(() -> insertLine("COMMISSION", 1))      // 음수타입 +1 거부
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("정의되지 않은 entry_type은 CHECK 제약으로 거부된다")
    void invalidEntryTypeRejected() {
        assertThatThrownBy(() -> insertLine("BONUS", 1_000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("정의되지 않은 source_type은 CHECK 제약으로 거부된다")
    void invalidSourceTypeRejected() {
        assertThatThrownBy(() -> insertLine("SALE", 1_000, "ORDER", 101, "evt-bad-source", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("멱등: 같은 (event, type, seller) 재적재는 복합 UNIQUE로 거부된다")
    void duplicateSameEventTypeSellerRejected() {
        // REFUND는 출처 부분 UNIQUE가 없으므로 거부 원인이 오직 uq_settline_event 임이 분명하다.
        insertLine(10, "REFUND", -1_000, "PAYMENT_CANCEL", 1, "evt-dup", null);
        assertThatThrownBy(() -> insertLine(10, "REFUND", -1_000, "PAYMENT_CANCEL", 2, "evt-dup", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("fan-out 허용①: 같은 이벤트라도 entry_type이 다르면(SALE/COMMISSION) 두 줄 공존")
    void fanOutDifferentEntryTypeAllowed() {
        // 한 PurchaseConfirmed 사건 → SALE(+) + COMMISSION(−). 같은 source_event_id, 같은 셀러.
        assertThat(insertLine(10, "SALE", 10_000, "ORDER_ITEM", 200, "evt-fan", null)).isEqualTo(1);
        assertThat(insertLine(10, "COMMISSION", -1_000, "ORDER_ITEM", 200, "evt-fan", null)).isEqualTo(1);
    }

    @Test
    @DisplayName("fan-out 허용②: 같은 이벤트라도 셀러가 다르면(멀티셀러 취소) 두 줄 공존")
    void fanOutDifferentSellerAllowed() {
        // 한 PAYMENT_CANCEL 사건이 셀러 A·B로 분기. 같은 source_event_id, entry_type 동일.
        assertThat(insertLine(10, "REFUND", -3_000, "PAYMENT_CANCEL", 300, "evt-multi", null)).isEqualTo(1);
        assertThat(insertLine(20, "REFUND", -2_000, "PAYMENT_CANCEL", 301, "evt-multi", null)).isEqualTo(1);
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
    @DisplayName("같은 출처(source_type, source_id)에 COMMISSION 두 줄은 부분 UNIQUE로 거부된다")
    void duplicateCommissionForSameSourceRejected() {
        insertLine("COMMISSION", -1_000, "ORDER_ITEM", 150, "evt-c1", null);
        assertThatThrownBy(() -> insertLine("COMMISSION", -900, "ORDER_ITEM", 150, "evt-c2", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 출처라도 SALE과 COMMISSION은 각각 한 줄씩 공존한다")
    void saleAndCommissionCoexistForSameSource() {
        assertThat(insertLine("SALE", 10_000, "ORDER_ITEM", 250, "evt-sale", null)).isEqualTo(1);
        assertThat(insertLine("COMMISSION", -1_000, "ORDER_ITEM", 250, "evt-comm", null)).isEqualTo(1);
    }

    @Test
    @DisplayName("존재하지 않는 cycle_id에 귀속하면 FK로 거부된다")
    void missingCycleRejected() {
        assertThatThrownBy(() -> insertLine("SALE", 10_000, "ORDER_ITEM", 350, "evt-fk", 999_999L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
