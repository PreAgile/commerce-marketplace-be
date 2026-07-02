-- 부분환불(M5-a) — 환불을 append-only 원장으로 전환한다. payment.refunded_amount 잠정 컬럼(V2 TODO)을 걷어내고
-- 누적환불 = SUM(refund.amount_minor) 도출로 바꾼다(AGENTS.md "잔액 컬럼 UPDATE 금지"·ADR-010 원장 철학과 정합).
--
-- 불변식 0 ≤ 누적환불 ≤ 결제금액을 DB가 강제한다:
--   · 하한(> 0)   = amount_minor > 0 CHECK. 환불은 "양수 사실"로 쌓고, 부호(정산 차감)는 settlement가 REFUND
--     음수 라인으로 표현한다(M5-b). 여기서 음수를 허용하면 환불로 잔액을 늘리는 우회가 열린다.
--   · 상한(≤ paid) = 여러 환불에 걸친 합이라 단일 CHECK로 못 박는다 → BEFORE INSERT 트리거로
--     SUM(환불) + NEW ≤ paid_amount 를 강제(주문 총액=Σ라인을 트리거로 박은 ADR-008과 같은 계열).
--     따닥 환불은 앱이 payment 행 FOR UPDATE로 직렬화하고(ADR-005 핫로우), 이 트리거가 최후 보루다.
--   · 멱등        = source_event_id UNIQUE. 같은 환불 재전달을 23505로 흡수(payment/shipping 선례).
--
-- 경계: refund.payment_id → payment(id)는 같은 BC(결제)라 FK. order_line_id는 하류 정산 귀속(셀러)용 타 BC
-- 논리 참조라 FK 아님(payment.order_id와 동급).

ALTER TABLE payment DROP CONSTRAINT ck_payment_refund_range;
ALTER TABLE payment DROP CONSTRAINT ck_payment_refund_status;
ALTER TABLE payment DROP COLUMN refunded_amount;

CREATE TABLE refund (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id      BIGINT      NOT NULL REFERENCES payment(id) ON DELETE RESTRICT,
    order_line_id   BIGINT      NOT NULL,                 -- 정산 귀속(셀러)용 논리 참조(FK 아님, 타 BC)
    amount_minor    BIGINT      NOT NULL,
    source_event_id TEXT        NOT NULL,                 -- 멱등의 기준(클라이언트 환불 토큰)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_refund_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_refund_evid_len        CHECK (char_length(source_event_id) <= 255),
    CONSTRAINT uq_refund_event           UNIQUE (source_event_id)
);
CREATE INDEX ix_refund_payment ON refund (payment_id);

-- 상한 강제: 이 환불을 포함한 누적환불이 결제금액을 넘으면 거부. check_violation(23514)로 던져 Spring이
-- DataIntegrityViolationException으로 번역한다(주문 총액 트리거 ADR-008과 동일 계열).
CREATE FUNCTION refund_within_paid() RETURNS trigger AS $$
DECLARE
    v_paid  BIGINT;
    v_total BIGINT;
BEGIN
    SELECT paid_amount INTO v_paid FROM payment WHERE id = NEW.payment_id;
    -- payment 미존재는 FK가 잡지만(트리거는 FK보다 먼저 돈다), 합산의 기준이 없으므로 여기서도 명시적으로 막는다.
    IF v_paid IS NULL THEN
        RAISE EXCEPTION 'refund references unknown payment %', NEW.payment_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    SELECT COALESCE(SUM(amount_minor), 0) + NEW.amount_minor INTO v_total
        FROM refund WHERE payment_id = NEW.payment_id;
    IF v_total > v_paid THEN
        RAISE EXCEPTION 'cumulative refund % exceeds paid_amount % for payment %', v_total, v_paid, NEW.payment_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_refund_within_paid
    BEFORE INSERT ON refund
    FOR EACH ROW EXECUTE FUNCTION refund_within_paid();
