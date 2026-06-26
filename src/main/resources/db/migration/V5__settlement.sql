-- 정산(Settlement) 컨텍스트 — 이 프로젝트의 헤드라인 역량①(멀티셀러 정산 + 4-way 대사)의 뿌리.
-- M1 마지막 도메인 슬라이스. 앞 슬라이스(order/payment/shipping)에 없던 두 패턴을 도입한다:
--   (1) 기간 겹침 금지 — 같은 셀러·유형의 정산 주기가 시간상 겹치면 매출이 이중 집계된다.
--       "범위 겹침"은 단일 CHECK·UNIQUE로 못 박으므로 EXCLUDE 제약(GiST)으로 DB가 강제한다.
--   (2) 부호 있는 append-only 원장 — 매출(+)·수수료(−)·환불(−)을 *행으로 쌓고*, 셀러 순지급은
--       SUM(amount_minor)로 도출한다. 잔액 컬럼 UPDATE 금지(AGENTS.md). 정정도 UPDATE가 아니라
--       반대부호 ADJUSTMENT 신규 행으로. entry_type별 부호 정합은 INSERT CHECK가 막는다.
--
-- 경계 규칙(AGENTS.md 황금률 1):
--   · settlement_line.cycle_id → settlement_cycle(cycle_id) 는 *같은* BC(정산)이므로 FK로 무결성을 챙긴다.
--   · seller_id 는 외부 셀러 도메인 논리 참조(FK 아님). source_id 도 타 컨텍스트(주문/결제) id 참조(FK 아님).

-- EXCLUDE에서 = (btree)와 && (gist range)를 한 제약에 섞으려면 btree_gist가 필요하다.
-- ※ 프로덕션 주의: CREATE EXTENSION은 보통 superuser 권한이 필요하다. 마이그레이션 실행 계정이
--   비-superuser면 여기서 실패하므로, 실 운영에선 확장 설치를 *권한 있는 부트스트랩*(DB 프로비저닝
--   단계 / DBA)으로 분리하고 이 마이그레이션은 "이미 설치돼 있음"을 전제하는 게 정석이다.
--   이 사이드 프로젝트는 "git clone → docker compose → migrate"가 그대로 돌아가는 self-contained 실행을
--   우선해 inline CREATE(IF NOT EXISTS, 멱등)를 유지한다(CI Testcontainers가 동작을 검증).
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE settlement_cycle (
    cycle_id     BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seller_id    BIGINT      NOT NULL,                    -- 셀러별 주기(전역 단일 주기 가정 제거)
    cycle_type   TEXT        NOT NULL,                    -- daily/weekly/monthly/express
    period_start TIMESTAMPTZ NOT NULL,
    period_end   TIMESTAMPTZ NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'OPEN',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 반열림 구간 [start, end): 인접 주기(prev.end == next.start)는 겹침이 아니다.
    CONSTRAINT ck_cycle_half_open CHECK (period_end > period_start),
    CONSTRAINT ck_cycle_type   CHECK (cycle_type IN ('daily', 'weekly', 'monthly', 'express')),
    CONSTRAINT ck_cycle_status CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED', 'PAID', 'CARRIED_OVER')),
    -- 같은 셀러·유형의 기간이 부분이라도 겹치면 거부(이중 집계 방지). 인접은 [)라 허용.
    CONSTRAINT ex_cycle_no_overlap EXCLUDE USING gist (
        seller_id WITH =, cycle_type WITH =,
        tstzrange(period_start, period_end, '[)') WITH &&)
);

-- ── 정산 원장 (append-only, 부호 amount, 1행=1사실) ──────────────────────────
CREATE TABLE settlement_line (
    line_id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seller_id       BIGINT      NOT NULL,                 -- 외부 셀러 도메인 논리 참조
    entry_type      TEXT        NOT NULL,                 -- 아래 ck_settline_type
    source_type     TEXT        NOT NULL,                 -- 아래 ck_settline_source_type
    source_id       BIGINT      NOT NULL,                 -- entry_type별 결정론적 출처 id(타 컨텍스트 참조, FK 아님)
    source_event_id TEXT        NOT NULL,                 -- 발행측 불변 고유 이벤트 ID(멱등의 진짜 기준)
    amount_minor    BIGINT      NOT NULL,                 -- 부호 있음(ck_settline_sign)
    eligible_at     TIMESTAMPTZ NOT NULL,                 -- 구매확정 시각(이 시점부터 정산 대상)
    cycle_id        BIGINT      REFERENCES settlement_cycle(cycle_id) ON DELETE RESTRICT,  -- 마감 시 귀속(같은 BC → FK)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_settline_evid_len CHECK (char_length(source_event_id) <= 255),
    CONSTRAINT ck_settline_source_type CHECK (source_type IN
        ('ORDER_ITEM', 'PAYMENT_CANCEL', 'MANUAL', 'SELLER_DEPOSIT')),
    CONSTRAINT ck_settline_type CHECK (entry_type IN
        ('SALE', 'COMMISSION', 'REFUND', 'REFUND_COMMISSION', 'RETURN_SHIPPING_FEE',
         'PG_FEE_NONREFUND', 'ADJUSTMENT', 'REPAYMENT')),
    -- entry_type별 부호 정합(0원·부호 플립 1차 차단). 선형 SUM 모델은 부호가 양변에서 같이 뒤집히면
    -- 상쇄돼 통과하므로, 부호는 SUM이 아니라 INSERT CHECK로 막는다(드리프트 봉쇄의 DB 강제 지점).
    CONSTRAINT ck_settline_sign CHECK (
        (entry_type = 'SALE'                AND amount_minor > 0) OR
        (entry_type = 'COMMISSION'          AND amount_minor < 0) OR
        (entry_type = 'REFUND'              AND amount_minor < 0) OR
        (entry_type = 'REFUND_COMMISSION'   AND amount_minor > 0) OR
        (entry_type = 'RETURN_SHIPPING_FEE' AND amount_minor < 0) OR
        (entry_type = 'PG_FEE_NONREFUND'    AND amount_minor < 0) OR
        (entry_type = 'REPAYMENT'           AND amount_minor > 0) OR
        (entry_type = 'ADJUSTMENT'          AND amount_minor <> 0)),
    -- 멱등키는 (이벤트, 항목유형, 셀러) 복합이다 — source_event_id 단독 UNIQUE면 fan-out을 중복으로 오인한다:
    --   · 한 사건이 SALE(+)·COMMISSION(−) 두 줄로 분기(같은 이벤트, entry_type 다름)
    --   · PAYMENT_CANCEL이 멀티셀러로 분기(같은 이벤트, seller_id 다름)
    -- 복합키는 이 정상 fan-out은 허용하면서, 진짜 중복(같은 event+type+seller 재수신)만 23505로 흡수한다.
    CONSTRAINT uq_settline_event UNIQUE (source_event_id, entry_type, seller_id)
);

-- 한 출처(source)당 SALE/COMMISSION은 각각 한 줄만(다른 멱등키로 중복 적재 방지)
CREATE UNIQUE INDEX uq_settline_sale ON settlement_line (source_type, source_id) WHERE entry_type = 'SALE';
CREATE UNIQUE INDEX uq_settline_comm ON settlement_line (source_type, source_id) WHERE entry_type = 'COMMISSION';
-- 셀러×주기 정산 집계 경로 + 미배정분(cycle_id NULL) 스윕
CREATE INDEX ix_settline_seller_cycle ON settlement_line (seller_id, cycle_id);
CREATE INDEX ix_settline_eligible     ON settlement_line (eligible_at) WHERE cycle_id IS NULL;
