-- 배송(Shipping) 컨텍스트 — 골든 시나리오 S3·S4(결제확정 이벤트 → 셀러별 배송 → 배송완료).
-- order/payment에 없던 두 패턴을 처음 도입한다:
--   (1) 이벤트 멱등 생성 — 결제확정(PaymentConfirmed) 이벤트는 at-least-once라 중복 도착한다.
--       같은 이벤트로 배송이 두 번 생기면 안 되므로 source_event_id로 멱등을 DB가 강제한다.
--   (2) append-only 상태 이력 — 상태를 UPDATE로 덮지 않고 전이를 행으로 쌓는다(언제·무엇에서·무엇으로).
--       "현재 상태는 단 하나"는 most_recent 부분 UNIQUE 인덱스로 DB가 강제한다.
--
-- 경계 규칙(AGENTS.md 황금률 1 — payment(V2) 선례와 동일):
--   · shipment.order_id 는 타 컨텍스트(주문) 식별자 참조이며 FK가 아니다(컨텍스트 간 직접 조인 금지).
--     ※ 설계 문서(system-design)는 단일 DB라 cross-context FK를 허용하나, 구현은 "추출 가능성"을 위해
--       더 순수한 DDD 노선(cross-BC FK 없음)을 택한다 — payment와 일관.
--   · shipment.seller_id 는 외부 셀러 도메인 논리 참조(FK 아님). 멀티셀러 = 한 주문에 셀러별 shipment N건.
--   · shipment_event.shipment_id 는 *같은* BC(배송)이므로 FK로 무결성을 챙긴다.

CREATE TABLE shipment (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        BIGINT      NOT NULL,                   -- 타 컨텍스트(주문) id 참조(FK 아님)
    seller_id       BIGINT      NOT NULL,                   -- 셀러별 출고분(정산 귀속 근거). 외부 도메인 논리 참조
    -- 어느 도메인 이벤트로 이 배송이 생성됐나. (source_event_id, seller_id) 멱등 키:
    -- 같은 결제확정 이벤트를 재수신해도 셀러당 shipment는 단 1건만 생긴다.
    source_event_id TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'READY',   -- 읽기 캐시. 권위는 shipment_event.most_recent
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_shipment_event_seller UNIQUE (source_event_id, seller_id),
    CONSTRAINT ck_shipment_evid_len CHECK (char_length(source_event_id) <= 255),
    CONSTRAINT ck_shipment_status CHECK (status IN
        ('READY', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED', 'RETURNED'))
);

-- 주문별/셀러별 배송 조회(셀러 정산이 seller_id로 출고분을 모은다)
CREATE INDEX ix_shipment_order  ON shipment (order_id);
CREATE INDEX ix_shipment_seller ON shipment (seller_id);

-- ── 배송 상태 이력 (append-only) ─────────────────────────────────────────────
-- 단일 status UPDATE 대신 전이를 행으로 쌓는다 → 과거 소실·동시 전이 lost update·불법 역전이 방지.
-- "이 배송의 현재 상태는 단 하나"를 most_recent 부분 UNIQUE가 DB로 강제한다.
-- 전이는 "직전 most_recent를 false로 내리고 + 새 행을 most_recent=true로" 한 트랜잭션에서 한다.
CREATE TABLE shipment_event (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_id  BIGINT      NOT NULL REFERENCES shipment(id) ON DELETE RESTRICT,  -- 같은 BC → FK
    from_status  TEXT,                                    -- 최초 전이는 NULL
    to_status    TEXT        NOT NULL,
    most_recent  BOOLEAN     NOT NULL DEFAULT TRUE,       -- 현재 상태(부분 UNIQUE로 단 하나 강제)
    sort_key     INT         NOT NULL,                    -- 전이 순서 0,1,2,…
    occurred_at  TIMESTAMPTZ NOT NULL,                    -- 사건 발생 시각(외부/택배사 기준)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_shipevent_to_status CHECK (to_status IN
        ('READY', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED', 'RETURNED'))
);

-- 배송당 현재 상태는 단 하나(부분 UNIQUE: most_recent 인 행만 대상)
CREATE UNIQUE INDEX uq_shipevent_most_recent ON shipment_event (shipment_id) WHERE most_recent;
-- 전이 순서 중복 금지
CREATE UNIQUE INDEX uq_shipevent_sortkey ON shipment_event (shipment_id, sort_key);
