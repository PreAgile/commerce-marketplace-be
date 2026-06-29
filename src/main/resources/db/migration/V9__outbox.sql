-- 트랜잭셔널 아웃박스 — 도메인 변경과 이벤트 발행을 한 트랜잭션으로 묶는 dual-write 해법(ADR-002).
-- 도메인 INSERT/UPDATE와 outbox INSERT가 같은 TX라 "DB는 커밋됐는데 메시지는 안 나감"이 구조적으로 불가능하다.
-- 실제 발행은 별도 릴레이가 사후(at-least-once)에 수행하고 소비자가 멱등으로 흡수한다(M3) — 여기선 적재까지만 한다.
CREATE TABLE outbox (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   BIGINT      NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,                       -- NULL = 미발행

    -- 사건당 1건. confirm 재시도/따닥이 같은 이벤트를 중복 적재하지 못하게 막는 멱등의 최후 보루.
    -- (현재 슬라이스는 집계당 이벤트 1종이라 이 키로 충분 — 반복 이벤트가 생기면 dedup 키를 확장한다.)
    CONSTRAINT uq_outbox_event UNIQUE (aggregate_type, aggregate_id, event_type)
);

-- 미발행 이벤트 릴레이 스윕용 부분 인덱스
CREATE INDEX ix_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
