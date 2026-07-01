-- 셀러별 수수료율 정책(settlement 소유). 배송완료(ShipmentDelivered) 소비 시 COMMISSION 라인 금액을
-- 계산하는 근거다(M4-b, ADR-010이 후속으로 미뤄둔 commission_policy 자리를 채운다).
-- rate_bps = basis points(1000 = 10%, 1500 = 15%). 셀러 항목이 없으면 앱이 기본율을 쓴다.
-- seller_id는 외부 셀러 도메인 논리 참조(FK 아님 — 다른 테이블과 일관).
CREATE TABLE commission_policy (
    seller_id  BIGINT      PRIMARY KEY,
    rate_bps   INT         NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 0%..100%. 상한 100%가 |COMMISSION| <= SALE 을 보장해 셀러 순지급(SUM)이 음수로 떨어지지 않게 한다.
    CONSTRAINT ck_commission_rate_range CHECK (rate_bps BETWEEN 0 AND 10000)
);
