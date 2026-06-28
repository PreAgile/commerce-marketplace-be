-- 장바구니 낙관적 락 — 같은 카트로의 동시 "담기"가 일으키는 lost update를 막는다.
-- addItem은 (읽기 → 수량 누적 → 쓰기)의 read-modify-write라 두 트랜잭션이 같은 값을 읽으면 한쪽 증분이 사라진다.
-- 애그리거트 루트(cart)에 version을 두고, 자식(cart_item) 변경 시에도 루트 version을 강제 증가시켜
-- (JPA OPTIMISTIC_FORCE_INCREMENT) 한 카트로의 동시 쓰기를 cart 행 하나에서 직렬화한다. 진 쪽은 충돌 → 재시도.
ALTER TABLE cart ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
