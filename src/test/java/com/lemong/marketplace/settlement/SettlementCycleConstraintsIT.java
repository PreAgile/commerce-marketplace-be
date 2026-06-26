package com.lemong.marketplace.settlement;

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

/**
 * 정산 주기의 <b>기간 겹침 금지</b>가 EXCLUDE(GiST) 제약으로 강제됨을 실 Postgres로 검증한다.
 *
 * <p>같은 셀러·유형의 정산 주기가 시간상 겹치면 매출이 이중 집계된다. "범위 겹침"은 단일 CHECK·UNIQUE로
 * 못 막으므로 {@code EXCLUDE USING gist (... tstzrange(...) WITH &&)} 로 DB가 막는다. 반열림 구간 [start,end)
 * 라 인접 주기(prev.end == next.start)는 겹침이 아니다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SettlementCycleConstraintsIT {

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        // settlement_line → settlement_cycle 순서로 비운다(FK).
        jdbc.sql("TRUNCATE TABLE settlement_line, settlement_cycle RESTART IDENTITY").update();
    }

    private int insertCycle(long sellerId, String type, String start, String end) {
        // tz 없는 날짜 문자열을 UTC로 *명시* 해석한다 — '::timestamptz'만 쓰면 실행 환경의 세션 타임존에
        // 따라 절대시각이 달라져 테스트가 환경 의존적이 된다(Gemini 지적). UTC로 고정해 결정적으로 만든다.
        return jdbc.sql("""
                        INSERT INTO settlement_cycle (seller_id, cycle_type, period_start, period_end)
                        VALUES (:seller, :type,
                                :start ::timestamp AT TIME ZONE 'UTC',
                                :end   ::timestamp AT TIME ZONE 'UTC')
                        """)
                .param("seller", sellerId)
                .param("type", type)
                .param("start", start)
                .param("end", end)
                .update();
    }

    @Test
    @DisplayName("정상 주기는 INSERT된다")
    void validCycleInserts() {
        assertThat(insertCycle(10, "weekly", "2026-06-01", "2026-06-08")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 셀러·유형의 기간이 겹치면 EXCLUDE 제약으로 거부된다")
    void overlappingCycleRejected() {
        insertCycle(10, "weekly", "2026-06-01", "2026-06-08");
        // [06-05, 06-12) 는 [06-01, 06-08) 와 06-05~06-08 구간이 겹침
        assertThatThrownBy(() -> insertCycle(10, "weekly", "2026-06-05", "2026-06-12"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("인접 주기(prev.end == next.start)는 반열림 [) 이라 겹침이 아니어서 허용된다")
    void adjacentCycleAllowed() {
        insertCycle(10, "weekly", "2026-06-01", "2026-06-08");
        assertThat(insertCycle(10, "weekly", "2026-06-08", "2026-06-15")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 기간이라도 셀러가 다르면 허용된다")
    void sameRangeDifferentSellerAllowed() {
        insertCycle(10, "weekly", "2026-06-01", "2026-06-08");
        assertThat(insertCycle(20, "weekly", "2026-06-01", "2026-06-08")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 셀러라도 cycle_type이 다르면 겹쳐도 허용된다")
    void sameSellerDifferentTypeAllowed() {
        insertCycle(10, "weekly", "2026-06-01", "2026-06-08");
        assertThat(insertCycle(10, "express", "2026-06-01", "2026-06-08")).isEqualTo(1);
    }

    @Test
    @DisplayName("period_end <= period_start 면 CHECK 제약으로 거부된다(역전 + 길이 0 경계)")
    void invertedPeriodRejected() {
        // 역전(end < start)
        assertThatThrownBy(() -> insertCycle(10, "weekly", "2026-06-08", "2026-06-01"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 길이 0(end == start) — ck_cycle_half_open이 strict '>'라 이것도 거부돼야 한다.
        // 이 경계가 없으면 제약이 실수로 '>='로 완화돼도 스위트가 통과해버린다.
        assertThatThrownBy(() -> insertCycle(10, "weekly", "2026-06-08", "2026-06-08"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("정의되지 않은 cycle_type은 CHECK 제약으로 거부된다")
    void invalidCycleTypeRejected() {
        assertThatThrownBy(() -> insertCycle(10, "hourly", "2026-06-01", "2026-06-08"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
