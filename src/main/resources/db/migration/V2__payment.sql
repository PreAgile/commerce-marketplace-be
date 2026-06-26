-- 결제(Payment) 컨텍스트 — 첫 도메인 테이블.
-- 불변식을 애플리케이션 코드가 아니라 DB 제약으로 박제한다(AGENTS.md):
--   · 멱등키 UNIQUE  → 따닥/중복 결제 차단(어느 코드 경로로 들어와도)
--   · 금액 CHECK     → 음수 결제·환불 초과를 INSERT 시점에 거부
-- order_id는 주문 컨텍스트의 식별자 참조이며 FK가 아니다(경계 규칙: 컨텍스트 간 직접 조인 금지).

CREATE TABLE payment (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        BIGINT      NOT NULL,            -- 타 컨텍스트 id 참조(FK 아님)
    idempotency_key TEXT        NOT NULL,
    paid_amount     BIGINT      NOT NULL,
    refunded_amount BIGINT      NOT NULL DEFAULT 0,
    status          TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_payment_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_payment_paid_nonneg CHECK (paid_amount >= 0),
    CONSTRAINT ck_payment_refund_range CHECK (refunded_amount >= 0 AND refunded_amount <= paid_amount),
    CONSTRAINT ck_payment_status CHECK (status IN ('PENDING', 'PAID', 'CANCELLED'))
);

-- 주문별 결제 조회용
CREATE INDEX ix_payment_order_id ON payment (order_id);
