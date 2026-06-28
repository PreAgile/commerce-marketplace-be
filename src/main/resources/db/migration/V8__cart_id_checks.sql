-- 외부 참조 id(buyer/product/seller) 양수 보장 — 도메인 팩토리가 1차로 막지만, raw INSERT·마이그레이션 등
-- 도메인 우회 경로까지 DB가 최후로 막아야 "DB가 최후 보루"(황금률 2)가 성립한다. 0/음수 id는 외부 도메인에서
-- 존재할 수 없는 참조라 무의미하고 버그 신호다.
ALTER TABLE cart      ADD CONSTRAINT ck_cart_buyer       CHECK (buyer_id > 0);
ALTER TABLE cart_item ADD CONSTRAINT ck_cartitem_product CHECK (product_id > 0);
ALTER TABLE cart_item ADD CONSTRAINT ck_cartitem_seller  CHECK (seller_id > 0);

-- 중복 인덱스 제거 — uq_cartitem_offer(cart_id, product_id, seller_id)가 cart_id 선두 인덱스를
-- 이미 제공하므로 cart_id 단독 조회·FK 검사에 그대로 쓰인다. ix_cartitem_cart는 이득 없이 쓰기 비용만 더한다.
DROP INDEX ix_cartitem_cart;
