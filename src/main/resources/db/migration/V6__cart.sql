-- 장바구니(Cart) — 골든 시나리오 S1의 입구(담기 → 주문). 주문 전 단계의 임시 staging.
-- cart는 정산 핵심 BC(order/payment/shipping/settlement)가 아닌 보조 컨텍스트다. 결제·정산 불변식과
-- 직교하므로 가볍게 둔다. 주문 생성(M2 PR#10)이 cart를 읽어 order/order_line으로 변환한다.
--
-- 경계:
--   · cart_item.cart_id → cart(id) 는 *같은* 애그리거트(cart)이므로 FK. cart는 transient라 ON DELETE CASCADE
--     (주문 전환 후 폐기되는 소유 자식 — order_line의 RESTRICT와 의도적으로 다름).
--   · product_id/seller_id 는 외부(카탈로그/셀러) 논리 참조(FK 아님). 단가는 담는 시점 스냅샷.

CREATE TABLE cart (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    buyer_id   BIGINT      NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE → ORDERED(주문으로 전환됨)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_cart_status CHECK (status IN ('ACTIVE', 'ORDERED'))
);

CREATE TABLE cart_item (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cart_id    BIGINT      NOT NULL REFERENCES cart(id) ON DELETE CASCADE,  -- 같은 애그리거트 → FK + CASCADE
    product_id BIGINT      NOT NULL,                    -- 카탈로그(외부) 논리 참조
    seller_id  BIGINT      NOT NULL,                    -- 멀티셀러 귀속(외부 셀러 도메인 논리 참조)
    unit_price BIGINT      NOT NULL,                    -- 담는 시점 단가 스냅샷
    quantity   INT         NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_cartitem_qty   CHECK (quantity > 0),
    CONSTRAINT ck_cartitem_price CHECK (unit_price >= 0),
    -- 같은 카트에 같은 상품은 한 줄(담기 = 수량 갱신 upsert). 멀티셀러는 product가 다르므로 자연히 여러 줄.
    CONSTRAINT uq_cartitem_product UNIQUE (cart_id, product_id)
);

CREATE INDEX ix_cartitem_cart ON cart_item (cart_id);
