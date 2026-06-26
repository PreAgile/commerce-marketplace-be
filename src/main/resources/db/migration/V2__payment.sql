-- 결제(Payment) 컨텍스트 — 첫 도메인 테이블.
-- 불변식을 애플리케이션 코드가 아니라 DB 제약으로 박제한다(AGENTS.md):
--   · 멱등키 UNIQUE      → 따닥/중복 결제 차단(어느 코드 경로로 들어와도)
--   · 금액/환불 CHECK    → 음수 결제·환불 초과·미결제 환불을 INSERT 시점에 거부
-- order_id는 주문 컨텍스트의 식별자 참조이며 FK가 아니다(경계 규칙: 컨텍스트 간 직접 조인 금지).

CREATE TABLE payment (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        BIGINT      NOT NULL,            -- 타 컨텍스트 id 참조(FK 아님)
    -- 멱등키는 클라이언트가 만든 "전역 고유 토큰"이라 전역 UNIQUE가 의도(주문별 복합키 아님).
    idempotency_key TEXT        NOT NULL,
    paid_amount     BIGINT      NOT NULL,
    -- TODO(refund 슬라이스/M2): refunded_amount는 잠정 컬럼이다. 환불을 append-only 원장(refund ledger)으로
    -- 옮기고 누적환불은 SUM으로 도출 예정 — AGENTS.md "잔액 컬럼 UPDATE 금지"와 정합시키기 위함.
    -- 현재(M1)는 "DB 제약으로 불변식을 박는다"를 보이는 데모용 컬럼.
    refunded_amount BIGINT      NOT NULL DEFAULT 0,
    status          TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_payment_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_payment_paid_nonneg CHECK (paid_amount >= 0),
    CONSTRAINT ck_payment_refund_range CHECK (refunded_amount >= 0 AND refunded_amount <= paid_amount),
    -- 미결제(PENDING/CANCELLED) 상태에서 환불액이 남아있는 비정상 데이터 차단
    CONSTRAINT ck_payment_refund_status CHECK (status = 'PAID' OR refunded_amount = 0),
    CONSTRAINT ck_payment_status CHECK (status IN ('PENDING', 'PAID', 'CANCELLED'))
);

-- 주문별 결제 조회용
CREATE INDEX ix_payment_order_id ON payment (order_id);
