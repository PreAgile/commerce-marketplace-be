-- 주문(Order) 컨텍스트 — 골든 시나리오 S1(장바구니→주문)의 뿌리 테이블.
-- 결제(V2)·배송·정산이 모두 이 order_id에 매달리므로(payment.order_id 등 id 참조) 먼저 세운다.
--
-- 불변식을 애플리케이션 코드가 아니라 DB 제약으로 박제한다(AGENTS.md):
--   · 단일 행 CHECK     → 수량>0, 금액 음수, line_amount = unit_price×quantity 를 INSERT 시점에 거부
--   · 교차 행 트리거    → "주문 총액 == Σ라인 금액"은 여러 행에 걸친 합이라 단일 CHECK로 못 박는다.
--                         커밋 시점 DEFERRABLE 제약 트리거로 강제(헤더 INSERT→라인 INSERT 순서를 한 TX로 허용).
--
-- 경계 규칙(AGENTS.md 황금률 1):
--   · order_line.order_id → orders(id) 는 FK다. order와 order_line은 *같은* BC(주문)이므로 구조 무결성을 DB가 챙긴다.
--   · order_line.seller_id 는 FK가 아니다. 셀러는 외부 도메인(스코프 밖)이라 값(논리 참조)으로만 둔다.
--     이 비대칭이 멀티셀러의 핵심 — 한 주문에 서로 다른 seller_id 라인이 공존한다(역량① 정산의 토대).
--   · product_id 도 카탈로그(외부)로의 논리 참조라 FK 없음.

CREATE TABLE orders (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    buyer_id     BIGINT      NOT NULL,                  -- 구매자(외부 유저 도메인 논리 참조, FK 아님)
    -- grand total: confirm 시 PG totalAmount와 대조할 load-bearing 값(브라우저 조작 방지).
    -- "Σ라인 금액과 일치"는 아래 제약 트리거가 커밋 시점에 강제한다.
    total_amount BIGINT      NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'CREATED',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_orders_total_nonneg CHECK (total_amount >= 0),
    CONSTRAINT ck_orders_status CHECK (status IN ('CREATED', 'PAID', 'CANCELLED'))
);

CREATE TABLE order_line (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     BIGINT      NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,  -- 같은 BC → FK로 무결성
    seller_id    BIGINT      NOT NULL,                  -- 멀티셀러 귀속 근거. 외부 셀러 도메인 논리 참조(FK 아님)
    product_id   BIGINT      NOT NULL,                  -- 카탈로그(외부) 논리 참조(FK 아님)
    quantity     INT         NOT NULL,
    unit_price   BIGINT      NOT NULL,                  -- 주문 시점 단가 스냅샷(서버 결정값)
    line_amount  BIGINT      NOT NULL,                  -- = unit_price × quantity (단일 행 CHECK로 박제)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_oline_qty_positive  CHECK (quantity > 0),
    CONSTRAINT ck_oline_price_nonneg  CHECK (unit_price >= 0),
    CONSTRAINT ck_oline_amount_calc   CHECK (line_amount = unit_price * quantity)
);

-- 주문별 라인 조회 + 총액 합산 트리거의 SUM 경로
CREATE INDEX ix_oline_order  ON order_line (order_id);
-- 셀러별 정산 귀속 조회(역량①에서 settlement가 seller_id로 라인을 모은다)
CREATE INDEX ix_oline_seller ON order_line (seller_id);

-- ── 교차 행 불변식: 주문 총액 == Σ라인 금액 ───────────────────────────────────
-- 단일 행 CHECK는 여러 라인에 걸친 SUM을 볼 수 없다. DEFERRABLE 제약 트리거로
-- *커밋 시점*에 헤더 total_amount 와 라인 합을 대조한다 → 헤더를 먼저 INSERT하고
-- 라인을 뒤이어 INSERT하는 자연스러운 순서를 한 트랜잭션 안에서 허용하면서도,
-- 커밋이 끝나는 순간 합이 안 맞으면 트랜잭션 전체를 거부한다.
CREATE OR REPLACE FUNCTION assert_order_total_matches_lines() RETURNS trigger AS $$
DECLARE
    v_order_id     BIGINT;
    v_header_total BIGINT;
    v_line_sum     BIGINT;
BEGIN
    -- 어느 주문을 검사할지: orders 트리거면 id, order_line 트리거면 order_id
    IF TG_TABLE_NAME = 'orders' THEN
        v_order_id := COALESCE(NEW.id, OLD.id);
    ELSE
        v_order_id := COALESCE(NEW.order_id, OLD.order_id);
    END IF;

    SELECT total_amount INTO v_header_total FROM orders WHERE id = v_order_id;
    IF NOT FOUND THEN
        RETURN NULL;   -- 주문이 (같은 TX에서) 삭제됨 → 검사할 헤더 없음
    END IF;

    SELECT COALESCE(SUM(line_amount), 0) INTO v_line_sum
    FROM order_line WHERE order_id = v_order_id;

    IF v_header_total <> v_line_sum THEN
        RAISE EXCEPTION
            'order % total_amount=% != sum(order_line.line_amount)=%',
            v_order_id, v_header_total, v_line_sum
            USING ERRCODE = 'check_violation';   -- 23514 → Spring DataIntegrityViolationException
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_oline_total_matches
    AFTER INSERT OR UPDATE OR DELETE ON order_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_order_total_matches_lines();

CREATE CONSTRAINT TRIGGER trg_orders_total_matches
    AFTER INSERT OR UPDATE ON orders
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_order_total_matches_lines();
