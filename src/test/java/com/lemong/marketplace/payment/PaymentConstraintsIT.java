package com.lemong.marketplace.payment;

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
 * 결제 불변식이 <b>DB 제약으로</b> 강제됨을 실 Postgres(Testcontainers)로 검증한다.
 *
 * <p>핵심: 검증을 애플리케이션 코드가 아니라 DB에 둔다 — 어떤 코드 경로(혹은 AI가 짠 우회 경로)로
 * 들어와도 잘못된 데이터는 INSERT 자체가 거부된다. mock이 아니라 진짜 DB라야 이 검증이 유효하다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentConstraintsIT {

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM payment").update();
    }

    private int insertPayment(String idempotencyKey, long paid, long refunded, String status) {
        return jdbc.sql("""
                        INSERT INTO payment (order_id, idempotency_key, paid_amount, refunded_amount, status)
                        VALUES (1, :key, :paid, :refunded, :status)
                        """)
                .param("key", idempotencyKey)
                .param("paid", paid)
                .param("refunded", refunded)
                .param("status", status)
                .update();
    }

    @Test
    @DisplayName("정상 결제는 INSERT된다")
    void validPaymentInserts() {
        assertThat(insertPayment("idem-1", 10_000, 0, "PAID")).isEqualTo(1);
    }

    @Test
    @DisplayName("음수 결제금액은 CHECK 제약으로 거부된다")
    void negativeAmountRejected() {
        assertThatThrownBy(() -> insertPayment("idem-2", -1, 0, "PAID"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("환불액이 결제액을 초과하면 CHECK 제약으로 거부된다")
    void refundExceedingPaidRejected() {
        assertThatThrownBy(() -> insertPayment("idem-3", 10_000, 10_001, "PAID"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("중복 멱등키는 UNIQUE 제약으로 거부된다 (따닥/중복 결제 차단)")
    void duplicateIdempotencyKeyRejected() {
        insertPayment("idem-dup", 10_000, 0, "PAID");
        assertThatThrownBy(() -> insertPayment("idem-dup", 20_000, 0, "PAID"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
