-- 베이스라인 마이그레이션.
-- 실제 도메인 스키마(주문·결제·배송·정산)는 이후 마이그레이션(V2~)에서 추가합니다.
-- 이 파일은 Flyway 파이프라인이 실 Postgres에 동작함을 증명하는 최소 마이그레이션이며,
-- "불변식은 DB 제약으로 박제한다"(AGENTS.md) 원칙을 단일행 CHECK로 미리 보여줍니다.

CREATE TABLE flyway_baseline_marker (
    id   SMALLINT     NOT NULL DEFAULT 1,
    note TEXT         NOT NULL DEFAULT 'baseline',
    CONSTRAINT pk_flyway_baseline_marker PRIMARY KEY (id),
    CONSTRAINT ck_flyway_baseline_single_row CHECK (id = 1)
);

INSERT INTO flyway_baseline_marker (id, note) VALUES (1, 'baseline');
