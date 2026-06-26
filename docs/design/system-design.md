# 마켓플레이스 시스템 설계 — 주문·결제·배송·정산

> 📎 설계 학습 노트에서 발췌·정리. 본문 일부 상호 링크(`[08]`·`[09]`·`SCHEMA-toss` 등)는 원 학습 레포 기준이라
> 이 저장소엔 없을 수 있습니다 — 개념 설명은 이 문서로 완결됩니다. 구현 범위는 [`implementation-spec.md`](./implementation-spec.md) 참조.

> **무엇**: [`SCHEMA-toss.md`](./SCHEMA-toss.md)의 주문·결제 코어 위에 **배송(fulfillment)·정산(settlement)** 을 새로 설계해, *셀러 입점형 오픈마켓*(쿠팡·마켓컬리·29CM류)의 **주문 → 결제 → 배송 → 정산** 전 라이프사이클을 하나로 잇는 시스템 설계서입니다.
> **페르소나**: 셀러가 입점해 물품을 팔고(마켓플레이스), 결제는 토스페이먼츠 PG, 배송은 택배, 정산은 셀러별 주기 정산. (배민형 즉시배달·라이더 배차는 범위 밖.)
> **기존 자산**: 주문·결제·카탈로그 DDL은 [`SCHEMA-toss.md`](./SCHEMA-toss.md), 풀 도메인(쿠폰·재고예약·정산·원장 38테이블)은 [`SCHEMA.md`](./SCHEMA.md)가 정본. 이 문서는 **그 위의 "어떻게 시스템으로 조립하나"** 를 다루고, DDL은 새 도메인(배송·정산)만 작성하고 나머지는 교차링크합니다 — 그래서 정산 원장·재고 예약·대사의 깊은 DDL은 SCHEMA.md를 정본으로 인용합니다.
> **검증 노트**: 이 문서는 우아한형제들·토스(SLASH)·토스페이먼츠 공식문서·전자상거래법(easylaw) 등 1차 출처로 교차검증해 개정했습니다(§11 출처). 정책값·외부 API 상태값은 출처 기준으로 정렬했습니다.

---

## 0. 무엇을 만드나 — 범위와 "이미 있는 것 / 새로 더할 것"

이미 [`SCHEMA-toss.md`](./SCHEMA-toss.md)에 깔린 자산이 도메인을 가리킵니다 — `product → product_variant(SKU) → product_price`(카탈로그), `order_item.seller_id`·`seller_funded_discount_minor`(셀러 입점·부담주체 split), `order_payment`/`payment_cancel`(토스 결제·부분환불). 즉 **마켓플레이스 물품 커머스의 뼈대가 이미 절반 서 있습니다.** 여기에 두 도메인을 더합니다.

```
 [이미 있음]                                  [새로 설계 — 이 문서]
 주문   orders·order_item·order_item_status   배송  shipment·shipment_item·shipment_event·return_request·return_item
       ·order_event                          정산  settlement_cycle·settlement_line·commission_policy
 결제   order_payment·payment_cancel               ·settlement·payout·payout_event  (+ 원장·재고예약은 SCHEMA.md 연계)
       ·pg_message_log
 카탈로그 product·product_variant·product_price
```

목표는 세 가지입니다 — ① 4개 도메인의 **책임 경계(bounded context)** 를 긋고, ② **이벤트로 느슨하게 연결**(주문→배송→정산)하며, ③ 돈·재고·정산의 **정합성**을 기존 멱등·append-only·대사 원칙([`03`](./03-idempotency-and-concurrency.md)·[`07`](./07-ledger-double-entry.md)·[`09`](./09-outbox-saga-and-audit.md))으로 지키는 것.

> **두 가지 정산 아키텍처 — 이 문서는 (B)** : 멀티셀러 결제 1건을 셀러별로 나눠 정산하는 방식은 둘로 갈립니다. **(A) 결제 시점 split형** — 결제 요청에 셀러별 분할(예: KICC `basketInfoList={productNo, productAmount, sellerId}`)을 *함께 등록*해 PG가 하위가맹점으로 자동이체. **(B) 사후 지급대행형** — 단일 결제로 받고, 정산은 플랫폼이 `settlement_line.seller_id`로 *사후 분해*한 뒤 지급대행(payout) API로 송금(토스페이먼츠 지급대행 모델). 이 문서는 토스 PG 전제이므로 **(B)** 를 택하고, 결제 시점 split 등록 단계는 두지 않습니다.

> **의도적 스코프 밖(범위 경계)** — ① **셀러 마스터·온보딩·KYC**: `seller_id`는 외부 셀러 도메인으로의 **논리 참조**이며 이 문서엔 `seller(seller_id)` 마스터 DDL을 두지 않습니다(같은 DB에 있으면 `REFERENCES seller(seller_id) ON DELETE RESTRICT` 권장). 단 토스 셀러 KYC 상태(`APPROVAL_REQUIRED→PARTIALLY_APPROVED→KYC_REQUIRED→APPROVED`)가 지급 선행조건이라 §4-3 payout 게이트에서 참조합니다. ② **롤링 리저브/비율 유보(holdback)**: 쿠팡 주정산식 30% 유보 같은 *사전 완충*은 코어 밖이며 [`08`](./08-settlement-and-reconciliation.md) §8.3로 위임합니다(사후 이월 carryover와 다른 개념 — §4-5).

---

## 1. 도메인 경계 — 4개 Bounded Context와 컨텍스트 맵

각 도메인은 **자기 데이터의 source of truth를 소유**하고, 다른 도메인과는 **이벤트(아웃박스)** 로만 통신합니다(직접 테이블 조인 금지 — 결합도·정합 사고의 근원).

```
 ┌──────────────┐  OrderPaid     ┌──────────────┐  Delivered/Confirmed  ┌──────────────┐
 │   주문 Order   │ ─────────────▶ │  배송 Fulfill  │ ───────────────────▶ │  정산 Settle   │
 │ 무엇을 샀나·상태 │                │ 언제 어디로 갔나 │                       │ 누구에게 얼마 줄까 │
 └──────┬───────┘                └──────────────┘                       └──────┬───────┘
        │ PaymentRequested / Refunded                                          │ Payout(지급대행)
        ▼                                                                       ▼
 ┌──────────────┐  PG 입금파일 대사    ┌──────────────┐                       ┌──────────────┐
 │  결제 Payment  │ ───────────────▶ │ 정산 입금 다리  │                       │  원장 Ledger   │ (SCHEMA.md §7)
 │ (토스 PG)      │                  │ pg_settlement │                       │  복식부기      │
 └──────────────┘                  └──────────────┘                       └──────────────┘
```

- **주문(Order)** — "무엇을, 누가, 얼마에 샀나"와 주문/항목 상태 이력. 진실은 `order_event`(append-only).
- **결제(Payment)** — "돈이 들어왔나/나갔나". 토스 PG 정렬, 이미 완성. 진실의 최종 권위는 토스 Query API(내 DB는 캐시).
- **배송(Fulfillment)** — "셀러가 언제 출고하고 택배가 어디까지 갔나". 진실은 `shipment_event`(append-only). 부분배송·반품·교환을 다룬다.
- **정산(Settlement)** — "각 셀러에게 얼마를 언제 주나". **두 다리** — ① 구매자→PG→플랫폼 *입금*을 받았는지 대사(§4-4), ② 플랫폼→셀러 *송금*(판매액 − 수수료 − 환불, §4-2~4-3). 진실은 정산 원장(append-only) + 복식부기 [`07`](./07-ledger-double-entry.md).

> **설계 원칙(컨텍스트 맵)**: 주문은 배송·정산을 *모른다*. 배송 완료/구매확정·환불 같은 **사실**만 이벤트로 흘려보내고, 정산은 그 사실들을 *받아* 자기 원장을 쌓는다. 정산 정책이 바뀌어도 주문 코드가 안 바뀐다(낮은 결합도).

<details>
<summary>🛡️ <b>"왜 애초부터 경계를 나누고 이벤트로만 통신하나 — 규모와 무관하게?" (면접 방어)</b></summary>

먼저 두 가지를 분리해야 한다(이 구분이 방어의 핵심) — **(A) 논리적 경계**(각 도메인이 자기 데이터의 진실을 소유하고 *남의 내부 테이블을 직접 조인·쓰지 않음*)와 **(B) 통신 수단**(동기 호출이냐 비동기 이벤트냐). **(A)는 규모와 무관하게 거의 항상 옳고, (B)는 케이스별 트레이드오프**다(§5에서 돈·재고는 동기로 남기는 이유).

**왜 (A) 경계는 규모 무관하게 긋나 — 경계는 *스케일*이 아니라 *복잡도·불변식*을 위한 것이라서.**
1. **변경 파급 차단** — 정산이 `orders` 테이블을 직접 조인하면, 주문 스키마를 바꾸는 순간 정산이 깨진다. 경계가 없으면 한 변경이 사방으로 번진다(결합 부채는 *복리*로 쌓인다).
2. **불변식 소유권** — 결제는 상태머신·멱등으로, 정산은 원장 SUM으로 자기 정합을 지킨다. 남이 그 테이블을 직접 쓰면 그 도메인의 불변식이 **우회**당한다(데이터 무결성 붕괴).
3. **인지 부하·팀 경계** — 경계 = 한 사람/팀이 독립적으로 이해·변경할 단위(콘웨이 법칙). 작은 코드베이스에서도 "주문 짜는 머리"와 "정산 짜는 머리"는 분리돼야 한다.
4. **회수 비용** — 처음에 안 그으면 나중에 떼어내는 비용이 폭발한다. 그래서 **"경계부터, 분리는 나중에"**(modular monolith first)가 실무 정설.

**왜 (B) 이벤트인가 — *남의 내부를 직접 건드리지 않는* 통신이라서.**
- **시간적 결합 분리** — 주문은 배송·정산이 *살아있는지* 몰라도 된다. 사실(PAID·DELIVERED)만 던지면 끝.
- **장애 격리** — 정산이 죽어도 주문은 받는다(직접 동기 호출이면 한쪽 장애가 전체를 막는다).
- **확장 자유** — 분석·알림 같은 새 소비자를 *발행자 수정 없이* 붙인다.
- **사실 기반 재처리** — append-only 이벤트라 감사·재구성·재처리가 된다.

**그런데 — 규모 무관하게 "마이크로서비스·비동기"를 하라는 게 아니다(정직한 경계).** (B)는 대가가 있다: **최종 일관성·멱등/순서/중복 처리(아웃박스)·디버깅 난이도**. 그래서:
- **작은 규모** → 한 앱(modular monolith) 안에서 **모듈 경계 + in-process 도메인 이벤트**(또는 모듈 간 application service 호출)로 충분. 핵심은 "**남의 테이블을 직접 조인·쓰지 않는다**"는 규율이지 별도 서비스가 아니다.
- **장애 격리·독립 배포·독립 스케일이 *실제로* 필요할 때** → outbox + 브로커 + 서비스 분리로 물리 분리.
- **즉시 정합이 필수인 경계**(돈·재고)는 규모와 무관하게 **동기·한 트랜잭션**으로 남긴다(§5).

**누가 계속 물으면 — 방어 사다리**

| 질문 | 답 |
|---|---|
| "그냥 한 DB에서 조인하면 간단하잖아요?" | 지금은 간단하나 결합이 쌓여 변경 파급·불변식 우회·장애 전파를 부른다. 논리 경계는 지금도 거의 공짜고, 나중에 떼는 건 비싸다. |
| "그럼 처음부터 마이크로서비스?" | 아니다. modular monolith로 시작 — 모듈 경계 + 도메인 이벤트(in-process). 물리 분리는 장애격리·독립배포·스케일이 *실제로* 필요할 때. |
| "이벤트는 최종일관성이라 복잡한데?" | 맞다. 그래서 돈·재고처럼 즉시 정합 필수인 건 동기·한 트랜잭션으로 남기고(§5), 나머지만 이벤트. 트레이드오프를 *경계별로* 건다. |
| "오버엔지니어링 아닌가?" | 경계 *긋는* 비용 < 안 그어서 생기는 결합 부채. 단 무지성 이벤트화는 오버엔지니어링이 맞다 → **경계는 항상, 비동기는 필요할 때**. |
| "규모 작으면 안 나눠도 되지 않나?" | 논리 경계는 나눈다(거의 공짜). 물리/비동기 분리는 미룬다. 둘을 섞어 말하면 진다. |

> 한 줄: **경계는 *스케일*이 아니라 *복잡도와 불변식*을 다스리려 긋는다 — 그래서 규모와 무관하게 논리 경계(남의 테이블 직접 안 건드림)는 항상 옳다. 다만 그 통신을 *비동기 이벤트로 물리 분리*할지는 장애격리·독립배포·스케일이 실제로 필요할 때의 트레이드오프다(돈·재고는 언제나 동기).**

</details>

---

## 2. 큰 그림 — 장바구니에서 정산까지 한 줄기

```
구매자                         우리 시스템(도메인 이벤트)                              셀러
 │ 장바구니→주문                orders+order_item INSERT (PENDING)
 │                            재고 차감/예약(아래 두 패턴 중 택1)
 │ 결제(토스 SDK)              order_payment(READY, idem 선점) → confirm → DONE
 │                            order_event(PAID, most_recent)  ──OrderPaid 이벤트──▶
 │                            (B패턴이면) 재고 예약→확정                              │ 출고 지시 수신
 │                                              shipment(READY) 셀러별 자동 생성 ◀───┘
 │                                              셀러가 운송장 채워 출고 → 추적 등록
 │ 배송 추적                   shipment_event(IN_TRANSIT→DELIVERED, most_recent)
 │                            ──Delivered 이벤트──▶ 자동확정 타이머(정책값·아래)
 │ (구매확정 or 자동확정)        order_event(CONFIRMED) ──PurchaseConfirmed──▶ 정산 대상 적재
 │                                              settlement_line INSERT(SALE+ / COMMISSION−)
 │ [환불] 부분취소/반품          payment_cancel append + 재고복원 ──Refunded──▶ settlement_line(REFUND− …)
 │                            정산 주기 마감: settlement 집계 → payout(지급대행) 송금  │ 정산금 수령
```

핵심 시점 두 개를 못 박습니다.

**(1) 재고는 두 정통 패턴 중 하나** — 예약→확정이 *유일* 정답은 아닙니다.
- **(A) 결제 시점 동기 즉시차감** — 예약 단계 없이 결제 시점에 조건부 원자 차감(`UPDATE … SET stock = stock - :q WHERE stock >= :q`, 0행=품절 거부), 취소만 비동기 멱등 복원. 배민 선물하기가 이 방식(RDB 트랜잭션 + Redis Set, [우아한형제들](https://techblog.woowahan.com/2709/)).
- **(B) 주문 시 예약 → 결제 성공 시 확정 → 미결제 만료 시 해제** — 결제 완료 전 가용 재고를 *선점*해 둔다. **이 문서는 (B)** 를 택합니다(셀러 출고 리드타임이 길어 결제~출고 사이 가용을 보존해야 하므로). 단 `SCHEMA-toss`의 `product_variant.stock_quantity` 단일 컬럼은 스스로 "간이"라 밝혔듯 예약 상태를 표현 못 하므로, **(B)를 쓰려면 [`SCHEMA.md`](./SCHEMA.md) §8-1 `reservations`**(`RESERVED/CONFIRMED/RELEASED`·`expires_at`·만료 스윕·`salable = on_hand − Σ(RESERVED & 미만료)`)를 정본으로 끌어옵니다([`04`](./04-inventory.md)).

> oversell 차단의 핵심은 "예약 유무"가 아니라 **차감이 조건부·원자적(`UPDATE … WHERE qty >= :q`)이냐**입니다. 예약은 *결제 완료 전 선점* 여부일 뿐입니다. (앞 판본의 "oversell vs 미결제 잠김의 균형"이라는 프레이밍은 부정확하여 정정.)

**(2) 정산 대상은 "결제 성공"이 아니라 "구매확정"** — 배송 완료 후 구매확정(또는 자동확정)돼야 환불 창의 대부분이 닫혀 셀러에게 줄 돈이 안정됩니다. **다만 구매확정 = 정산 100% 확정은 아닙니다** — 자동확정 후에도 반품·교환 기간이 남아, 쿠팡 주정산은 매출의 70%만 선지급하고 30%를 익익월로 유보합니다(비율 유보, §10). 11번가·스마트스토어 일반정산·토스 지급대행은 유보 없이 100% 지급 — 비율 유보는 쿠팡 특유 변형이라 코어는 후자를 기본으로 둡니다.

> **자동확정일은 상수가 아니라 정책값**입니다(§5 `auto_confirm_policy`). 쿠팡 배송완료 **D+7**, 네이버·스마트스토어·11번가 **D+8**(모두 영업일 아닌 **달력일**), **배송추적 불가** 상품(퀵·방문수령·화물·추적불가 택배)은 발송처리일 **D+28**, 해외 **D+45**의 별도 fallback 타이머가 적용됩니다. 이 fallback이 없으면 `DELIVERED` 이벤트가 영영 안 오는 추적불가 배송이 자동확정·정산에 못 들어가는 **정산 미아**가 됩니다(§3-1 `auto_confirm_due`).

---

## 3. 배송 도메인 설계 (신규 DDL)

### 3-1. `shipment` — 배송 단위 (셀러·박스 단위, 운송장 보유)

한 주문이 **여러 셀러·여러 박스**로 쪼개져 나갈 수 있으므로(부분배송), 배송 단위는 주문이 아니라 `shipment`입니다.

```sql
CREATE TABLE shipment (
    shipment_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        BIGINT       NOT NULL REFERENCES orders(order_id) ON DELETE RESTRICT,
    seller_id       BIGINT       NOT NULL,                -- 어느 셀러의 출고분(정산 귀속 근거). 외부 셀러 도메인 논리참조
    carrier_code    TEXT,                                 -- ★ 추적공급사 코드체계(CJ/한진 등). 공급사 교체 시 매핑 필요. 출고 전 NULL
    tracking_no     VARCHAR(64),                          -- 운송장 번호. 출고 전 NULL
    trackable       BOOLEAN      NOT NULL DEFAULT TRUE,    -- ★ 추적 가능 여부(퀵·방문수령·화물=FALSE → 발송일 fallback 타이머)
    status          TEXT         NOT NULL DEFAULT 'READY', -- 캐시(권위는 shipment_event.most_recent)
    ship_address    JSONB        NOT NULL,                 -- 배송지 스냅샷(주문 시점 동결 — 주소 변경 소급 방지)
    shipped_at      TIMESTAMPTZ,                           -- 발송처리 시각(추적불가 fallback 타이머 기준)
    delivered_at    TIMESTAMPTZ,
    auto_confirm_due TIMESTAMPTZ,                          -- ★ 자동확정 예정 시각(추적가능=delivered+정책일 / 추적불가=shipped+정책일)
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ship_status CHECK (status IN
        ('READY','PICKED_UP','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED','FAILED','RETURNED'))
);
CREATE UNIQUE INDEX uq_ship_tracking ON shipment (carrier_code, tracking_no)
    WHERE tracking_no IS NOT NULL;                         -- 운송장 중복 방지 + 추적 콜백 매칭 키
CREATE INDEX ix_ship_order  ON shipment (order_id);
CREATE INDEX ix_ship_seller ON shipment (seller_id);
CREATE INDEX ix_ship_autoconfirm ON shipment (auto_confirm_due)            -- 자동확정 스윕(추적가능·불가 합집합)
    WHERE delivered_at IS NULL AND status NOT IN ('DELIVERED','RETURNED'); -- 미확정만
```

- **배송지를 스냅샷(`ship_address`)** 으로 동결 — 회원 주소록이 바뀌어도 "그때 어디로 보냈나"는 불변([`02`](./02-order-modeling.md)).
- **`carrier_code`는 추적공급사(스윗트래커/Delivery Tracker 등) 코드체계** — 공급사 고유 코드라 공급사 교체 시 `carrier_code_mapping(internal ↔ provider)` 매핑이 필요합니다(§3-3).
- **`trackable`·`auto_confirm_due`가 정산 미아 방지의 핵심** — 자동확정 후보를 **합집합**으로 잡습니다: 추적가능 = `DELIVERED.occurred_at + delivered_days`, 추적불가 = `shipped_at + untracked_days`. 스케줄러는 `DELIVERED` 이벤트뿐 아니라 "발송됐으나 추적불가 & `shipped_at + untracked_days` 경과 & 미확정" 건도 스윕합니다.

<details>
<summary>🔗 <b>"경계는 조인 금지라며 — 근데 `order_id`엔 FK가 걸려 있잖아?" (FK ≠ join, 그리고 order_id는 FK인데 seller_id는 왜 아닌가)</b></summary>

정확한 지적이다. `order_id`엔 `REFERENCES orders` FK가 있고 `seller_id`엔 없다 — 이 비대칭이 핵심이다. **"조인 금지"와 "FK 금지"는 다른 축**이라서 그렇다.

- **"조인 금지"가 막는 것 = *행위적* 결합** — 배송 도메인이 자기 로직을 굴리려고 `orders` 테이블을 *직접 조인해 주문의 업무 상태(금액·할인·상태)를 읽는 것*. 이건 금지다. 배송은 그 사실을 **이벤트(OrderPaid)로 받아** 자기 데이터로 박제하고 그걸로 동작한다(`ship_address` 스냅샷이 그 예 — orders를 조인해 매번 읽지 않는다).
- **FK가 주는 것 = *구조적* 무결성** — `order_id → orders` FK는 "존재하지 않는 주문에 매달린 고아 배송"을 DB가 막아줄 뿐, 주문의 *컬럼을 읽어 로직을 굴리는 게 아니다*. 즉 FK는 조인(행위 결합)보다 훨씬 약한 결합이다.

**왜 order_id는 FK, seller_id는 아닌가** — 이 문서의 baseline은 §1 토글의 결론대로 **단일 DB modular monolith**다. 주문·결제·배송·정산은 *같은 DB의 모듈*이라 그들 사이엔 FK로 무결성을 챙긴다. 반면 **셀러는 §0에서 "외부 셀러 도메인(스코프 밖)"으로 선언**했으므로 — 이미 *다른 DB/서비스처럼* 취급 — `seller_id`는 FK 없이 **값(논리 참조)**으로만 둔다. 즉 `order_id`(내부 모듈, FK) vs `seller_id`(외부 도메인, 값)의 비대칭은 **결합 등급의 의도된 표현**이다.

**서비스/DB를 물리 분리하면?** 크로스-DB FK는 불가능하므로 `order_id` FK를 **떼고 `seller_id`처럼 값-only**로 바꾼다. 그러면 무결성은 DB가 아니라 **"유효한 OrderPaid 이벤트를 받았을 때만 shipment를 만든다"**는 이벤트 기반(최종) 정합으로 옮겨간다. (그래서 더 순수한 DDD 진영은 "추출 가능성"을 위해 모놀리스에서도 크로스-컨텍스트 FK를 안 거는 것을 선호한다 — 이 문서는 *단일 DB 단계에선 FK로 무결성을 챙기고, 분리 시 떼는* 실용 노선을 택했다.)

> 한 줄: **FK(구조적 존재 무결성) ≠ 조인(행위적 상태 읽기). 단일 DB 모놀리스 단계에선 내부 모듈 간 FK는 허용하고(고아 방지), 외부 도메인(seller)은 값-only. 물리 분리 시 그 FK도 떼고 이벤트 정합으로 전환한다.**

</details>

<details>
<summary>🧬 <b>Spring Data JPA로 어떻게 — "객체 참조 ❌ → id 참조 ✅"(@ManyToOne Order 대신 Long orderId) + 실제 SQL</b></summary>

**INSERT는 두 테이블 동시 insert가 아니다.** 주문은 *주문 도메인*이 결제 시점에 먼저 INSERT, shipment는 *배송 도메인*이 `OrderPaid` 이벤트를 받아 **나중에** INSERT한다. FK는 cascade(같이 넣기)가 아니라 **존재 확인**일 뿐:
```sql
INSERT INTO shipment (order_id, seller_id, ship_address, ...) VALUES (42, 7, '{...}', ...);
--   ↑ DB는 orders.order_id=42 '존재'만 확인(PK 인덱스 lookup). orders INSERT 안 함. JOIN 아님.
```

**분리의 실체는 자바 *객체 참조* 수준이다 — DDL이 아니라.** 두 방식:
```java
// ❌ (A) 객체 참조 — shipment가 Order 엔티티를 '안다'(import·navigate 가능)
@ManyToOne(fetch = LAZY) @JoinColumn(name = "order_id")
private Order order;     // → shipment.getOrder().getStatus()로 주문 내부를 읽게 됨 = cross-context 결합

// ✅ (B) id 참조 — shipment는 Order 클래스를 '모른다'(그냥 Long)
@Column(name = "order_id")
private Long orderId;
```
**(B)가 정석**(DDD: "다른 애그리거트는 객체가 아니라 *식별자*로 참조"). DB FK는 (A)(B) **둘 다 그대로** 있다 — 분리는 DDL이 아니라 **객체 그래프**에서 일어난다. (B)면 shipment 모듈이 `Order` 클래스를 **import조차 안 해** 컴파일 결합이 없고, 런타임에 orders로 **navigate할 길 자체가 없다**(실수로 order 읽기가 코드상 불가능). (A)였다면 `getOrder().getStatus()` 한 줄이 `SELECT ... FROM orders WHERE order_id=42` 추가 쿼리(cross-context read)를 부른다 — 그게 피하려는 것.

**READ — 도메인 로직은 orders를 조인하지 않는다.** shipment는 자기 테이블만 읽고, 필요한 주문 정보(배송지)는 이미 `ship_address` 스냅샷에 박제돼 있다:
```sql
SELECT * FROM shipment WHERE order_id = 42;   -- orders 조인 0. 자기 테이블만.
```

**"서로 모른 채 진행"의 실체** — 배송은 주문을 `Long orderId`로만 알고, 그 외 정보는 **이벤트로 받아 자기 데이터(ship_address)로 박제**해 쓴다. 주문은 **배송의 존재를 모른다**(`OrderPaid`만 발행, 배송이 듣든 말든 무관). 둘을 잇는 건 `Long orderId` 값 + DB FK(무결성)뿐.

> **주문×배송을 같이 보는 화면**이 필요하면? 그건 *도메인 로직*이 아니라 *읽기 모델/리포팅* 관심사라, 별도 read model(CQRS)·native/QueryDSL 조인으로 **의도적으로 분리**해 처리한다(§9). 도메인 쓰기 로직은 조인 안 한다.
>
> **물리 분리 시** — `order_id` FK를 떼도 (B)는 이미 `Long orderId`라 **코드 변경이 거의 없다**(추출 가능성 — (B)를 쓰는 또 다른 이유).

</details>

### 3-2. `shipment_item` — 무엇을 몇 개 담아 보냈나 (부분배송 매핑)

```sql
CREATE TABLE shipment_item (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_id  BIGINT NOT NULL REFERENCES shipment(shipment_id) ON DELETE RESTRICT,
    line_id      BIGINT NOT NULL REFERENCES order_item(line_id)  ON DELETE RESTRICT,
    quantity     INT    NOT NULL CHECK (quantity > 0),
    CONSTRAINT uq_shipitem UNIQUE (shipment_id, line_id)   -- 한 배송 내 같은 line 중복만 막음(아래로 over-ship 추가 방어)
);
CREATE INDEX ix_shipitem_line ON shipment_item (line_id);
```

`uq_shipitem`만으로는 **같은 `line`을 여러 shipment에 나눠 담는 부분배송에서 누적합이 주문 수량을 넘는 over-ship**을 못 막습니다(단일 행 CHECK는 여러 shipment 걸친 SUM을 못 봄). 그래서 `order_item`에 누적 카운터를 두고 **조건부 원자 UPDATE로 게이팅**합니다(`*_refunded_so_far`와 동형 — [`05`](./05-payment-and-refund.md)).

```sql
ALTER TABLE order_item ADD COLUMN quantity_shipped_so_far INT NOT NULL DEFAULT 0;
ALTER TABLE order_item ADD CONSTRAINT ck_oli_qty_ship CHECK (quantity_shipped_so_far <= quantity);
-- shipment_item INSERT 전, 같은 트랜잭션에서:
--   UPDATE order_item SET quantity_shipped_so_far = quantity_shipped_so_far + :q
--    WHERE line_id = :id AND quantity_shipped_so_far + :q <= quantity;   -- affected=0 → over-ship 거부
--   (0행이 아니면 그때만 shipment_item INSERT)
```

### 3-3. `shipment_event` — 배송 상태/추적 이력 (append-only)

```sql
CREATE TABLE shipment_event (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_id       BIGINT      NOT NULL REFERENCES shipment(shipment_id) ON DELETE RESTRICT,
    from_status       TEXT,
    to_status         TEXT        NOT NULL,               -- READY→PICKED_UP→IN_TRANSIT→…→DELIVERED
    most_recent       BOOLEAN     NOT NULL DEFAULT TRUE,  -- 현재 상태(역전이 방지)
    sort_key          INT         NOT NULL,
    carrier_dedup_key TEXT,                               -- ★ 추적 콜백 중복차단 키(carrier_code+tracking_no+to_status+occurred_at 해시)
    carrier_raw       JSONB,                              -- 택배사 추적 API 원문(감사·재현)
    occurred_at       TIMESTAMPTZ NOT NULL,               -- 택배사가 알려준 사건 시각
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_shipevent_sortkey UNIQUE (shipment_id, sort_key)
);
CREATE UNIQUE INDEX uq_shipevent_most_recent ON shipment_event (shipment_id) WHERE most_recent;
CREATE UNIQUE INDEX uq_shipevent_carrier_dedup ON shipment_event (carrier_dedup_key)
    WHERE carrier_dedup_key IS NOT NULL;                  -- 콜백 INSERT … ON CONFLICT DO NOTHING으로 중복 흡수
```

- **택배 추적은 임의 웹훅이 아니라 register-then-callback** 구조입니다 — 운송장을 `(carrier_code, tracking_no, callback_url)`로 추적공급사에 **먼저 등록**해야 상태변화마다 콜백 POST가 옵니다. 그래서 매칭/중복 식별은 *우리가 발급한 키*가 아니라 **`carrier_code + tracking_no`(+ 상태 + 시각)** 입니다(외부는 우리 내부 ID를 모름).
- **중복차단(`carrier_dedup_key`)과 역전이 방지(`most_recent` + 상태머신 가드)는 분리된 두 장치**입니다 — 콜백은 순서 뒤바뀜·중복으로 오므로, dedup_key가 같은 콜백 재수신을 흡수하고, `most_recent` 부분 UNIQUE + 가드가 늦은 이벤트의 역전이를 막습니다([`01-append-only`](../flab-mentoring/2026-06-20/01-append-only-vs-state-update.md)).

### 3-4. `return_request` · `return_item` — 반품/교환 (환불의 물리 트리거)

```sql
CREATE TABLE return_request (
    return_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      BIGINT      NOT NULL REFERENCES orders(order_id) ON DELETE RESTRICT,
    type          TEXT        NOT NULL,                   -- RETURN(반품·환불) / EXCHANGE(교환)
    status        TEXT        NOT NULL DEFAULT 'REQUESTED',
    reason        TEXT        NOT NULL,
    reason_type   TEXT        NOT NULL,                   -- BUYER_CHANGE(단순변심)/DAMAGED/WRONG_DELIVERY …(귀책=배송비·수수료 분기)
    idempotency_key TEXT      NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_return_type   CHECK (type IN ('RETURN','EXCHANGE')),
    CONSTRAINT ck_return_status CHECK (status IN
        ('REQUESTED','PICKUP_SCHEDULED','RETRIEVED','INSPECTED','APPROVED','REJECTED','COMPLETED')),
    CONSTRAINT uq_return_idem UNIQUE (idempotency_key)
);

CREATE TABLE return_item (                                -- 부분반품 매핑 + over-return·검수 분해
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    return_id           BIGINT NOT NULL REFERENCES return_request(return_id) ON DELETE RESTRICT,
    line_id             BIGINT NOT NULL REFERENCES order_item(line_id)       ON DELETE RESTRICT,
    quantity            INT    NOT NULL CHECK (quantity > 0),  -- 반품 요청 수량
    received_quantity   INT    NOT NULL DEFAULT 0,             -- 실제 회수 수량
    restock_quantity    INT    NOT NULL DEFAULT 0,             -- 검수 후 정상 재입고 수량
    damaged_quantity    INT    NOT NULL DEFAULT 0,             -- 파손·폐기(재입고 안 함)
    exchange_to_variant_id BIGINT,                             -- 교환 시 교체 SKU(NULL=반품)
    CONSTRAINT uq_returnitem UNIQUE (return_id, line_id),
    CONSTRAINT ck_returnitem_inspect CHECK (received_quantity = restock_quantity + damaged_quantity)
);
-- 불변식: ① Σ return_item.quantity (line별) ≤ Σ shipment_item.quantity (발송수량) — over-return 방어
--         ② 재고 복원은 restock_quantity만(파손분 미복원, oversell·품질사고 방지), 멱등키 restore:{return_id}:{line_id}
```

- 반품은 **환불(`payment_cancel`)의 물리 트리거**입니다. 흐름: 접수 → 회수 → 검수(정상/파손 분해) → 결제 취소(부분) + **정상분만** 재고 복원 + 정산 차감. `reason_type`(귀책)이 반품배송비·수수료 환원 여부를 가릅니다(§6 조합표).
- 교환(`EXCHANGE`)은 회수 + 재출고 + 차액결제가 얽힌 별도 흐름이라 **§3-5**로 분리합니다.

### 3-5. 교환(EXCHANGE) 전용 플로우 — 회수 + 재출고 + 차액결제

교환은 반품과 다릅니다 — 회수와 신규 출고가 동시에 일어나고, 옵션 변경 시 차액 결제가 발생하며, 자동확정 타이머를 재설정해야 합니다(`EXCHANGE` enum만 두고 RETURN과 동일 취급하면 데드 enum).

1. **승인(`INSPECTED→APPROVED`)** 시 ① 회수 = 반품과 동일 검수(`received/restock/damaged` 분해), ② 교체 출고 = **새 outbound `shipment` INSERT**(`return_item.exchange_to_variant_id`로 교체 SKU, 원 `return_id` 연결). 회수 복원과 교체 출고차감을 같은 멱등 경계로.
2. **옵션 차액** — 교체 variant 가격 > 원가 → 추가결제(`order_payment` 신규), < 원가 → 부분환불(`payment_cancel` append). 차액분도 별도 `SALE`/`REFUND` 정산 라인으로 원장 반영.
3. **타이머 재설정** — 교체 배송 `DELIVERED`(추적가능) 또는 재발송처리일(추적불가)부터 자동확정 타이머 재산정(기존 타이머 무효화). 검수·분쟁 중에는 자동확정 hold.
4. **셀러 교체재고 부재** 시 쿠팡식 **교환 → 반품 자동 전환** 폴백.
5. `order_event`/`order_item_status`에 `EXCHANGE_REQUESTED→EXCHANGE_SHIPPED` 전이 추가.

---

## 4. 정산 도메인 설계 (신규 DDL)

마켓플레이스 정산의 본질: **플랫폼이 구매자 돈을 받아 두었다가(에스크로), 셀러 몫(판매액 − 수수료 − 환불)을 주기로 모아 지급**. 정산은 회계 사실이라 **append-only 원장** 위에서 도출하고, 셀러 정산서는 그 집계 캐시입니다([`07`](./07-ledger-double-entry.md)·[`08`](./08-settlement-and-reconciliation.md)).

> **에스크로는 비유가 아니라 법적 제약** — 상품 공급 *전* 대금을 받는 선지급식 거래는 **전자상거래법 제24조**상 결제대금예치(에스크로) 또는 소비자피해보상보험으로 구매안전서비스를 제공할 의무가 있습니다(신용카드 거래는 카드사 결제취소로 갈음하여 제외, 배송확인 불가·분할공급도 제외). 즉 "구매확정 후 정산"은 환불 최적화이자 **법정 에스크로 절차와 같은 방향**입니다. 결제수단 분기는 `SCHEMA-toss`의 `order_payment.use_escrow`(현금/계좌이체→TRUE, 카드→FALSE)로 모델링합니다. (적용 금액 10만/5만원 등은 법령 본문이 아닌 운영 관행이라 법적 근거로 단정하지 않습니다.)
>
> **직접 송금은 전자금융거래법 리스크** — 통신판매중개 플랫폼이 셀러에게 직접 송금하면 무허가 지급결제대행에 해당할 수 있어, **라이선스 보유 지급대행 PG(payout)** 를 거치고 **정산예치금을 운영자금과 분리(자금 분별관리)** 해야 합니다(티메프 commingling 반례). payout은 그래서 추상 PG가 아니라 **토스페이먼츠 "지급대행" 실상품**으로 모델링합니다(§4-3).

### 4-1. `commission_policy` — 수수료율 (이력, 시점에 단일 율)

```sql
CREATE TABLE commission_policy (
    policy_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seller_id    BIGINT,                                  -- NULL = 카테고리/전역 기본
    category_id  BIGINT,
    rate         NUMERIC(7,6) NOT NULL CHECK (rate >= 0 AND rate <= 1),  -- 0.10 = 10%
    valid_from   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    valid_to     TIMESTAMPTZ,                             -- NULL = 현재 유효
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_commission_period CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ex_commission_no_overlap EXCLUDE USING gist (        -- 한 시점에 율은 하나(겹침 금지)
        COALESCE(seller_id, -1) WITH =, COALESCE(category_id, -1) WITH =,  -- NULL을 sentinel로 접어 겹침검사 동작
        tstzrange(valid_from, COALESCE(valid_to, 'infinity'), '[)') WITH &&)
);  -- 전제: CREATE EXTENSION btree_gist
CREATE INDEX ix_commission_lookup ON commission_policy (seller_id, category_id, valid_from);
```

- 수수료율은 **시점에 묶입니다** — 정산 라인 생성 시점의 유효 율을 **스냅샷으로 복사**해 `settlement_line.commission_rate`에 박습니다(나중에 율이 바뀌어도 과거 정산 불변, `product_price`와 동형). [`SCHEMA.md`](./SCHEMA.md) `commission_rates`의 `EXCLUDE USING gist`와 동형으로 **한 시점에 율은 하나**를 강제합니다.
- **NULL 의미론** — `seller_id`/`category_id`가 NULL(전역 기본)이면 GiST EXCLUDE의 `WITH =`가 NULL=NULL을 겹침으로 안 보므로, `COALESCE(…, -1)`로 NULL을 단일 sentinel로 접어 겹침검사가 동작하게 합니다. **"가장 구체적 매칭 우선"**(seller+category > seller/category-only > 전역)은 EXCLUDE가 아니라 lookup 쿼리(`ORDER BY specificity DESC LIMIT 1`)가 책임집니다 — EXCLUDE는 같은 specificity 단계 내 시점 단일성만 보장.

### 4-2. `settlement_cycle` — 정산 주기 (반열림·겹침금지·셀러별, 미수금 이월 포함)

> 정의 순서: `settlement_line`이 이 테이블을 FK로 참조하므로 **`settlement_cycle`을 먼저 정의**합니다(인라인 `REFERENCES`는 대상이 실행 시점에 존재해야 함 — 앞 판본의 전방참조 버그 수정).

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE settlement_cycle (
    cycle_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seller_id       BIGINT       NOT NULL,                -- ★ 셀러별 주기(전역 단일 주기 가정 제거)
    cycle_type      TEXT         NOT NULL,                -- daily/weekly/monthly/express
    period_start    TIMESTAMPTZ  NOT NULL,                -- ★ TIMESTAMPTZ(귀속 기준 eligible_at과 같은 정밀도)
    period_end      TIMESTAMPTZ  NOT NULL,
    cutoff_at       TIMESTAMPTZ,                          -- 마감 스냅샷 시각(반열림 [start, cutoff))
    payout_due_at   TIMESTAMPTZ,                          -- 지급 예정일(영업일 캘린더로 산출)
    status          TEXT         NOT NULL DEFAULT 'OPEN', -- OPEN→CLOSING→CLOSED→PAID(+CARRIED_OVER)
    carryover_in_minor  BIGINT   NOT NULL DEFAULT 0,      -- ★ 전 주기에서 넘어온 미수금(항상 ≤ 0)
    carryover_out_minor BIGINT   NOT NULL DEFAULT 0,      -- ★ 이번 주기 net<0이면 다음으로 이월(항상 ≤ 0)
    closed_at       TIMESTAMPTZ,
    CONSTRAINT ck_cycle_half_open CHECK (period_end > period_start),
    CONSTRAINT ck_cycle_status CHECK (status IN ('OPEN','CLOSING','CLOSED','PAID','CARRIED_OVER')),
    CONSTRAINT ck_cycle_type   CHECK (cycle_type IN ('daily','weekly','monthly','express')),
    CONSTRAINT ck_carryover_nonpos CHECK (carryover_in_minor <= 0 AND carryover_out_minor <= 0),
    CONSTRAINT ex_cycle_no_overlap EXCLUDE USING gist (   -- 같은 셀러·유형의 기간 부분겹침 금지(이중집계 방지)
        seller_id WITH =, cycle_type WITH =,
        tstzrange(period_start, period_end, '[)') WITH &&)
);
-- payout_due_at은 business_calendar(cal_date, is_business_day, region)로 마감일부터 영업일 N건 전진
-- (쿠팡 주정산=주 마지막일+15영업일, 토스 EXPRESS=영업일 08~15시 당일). 캘린더는 SCHEMA.md/08장 위임.
```

### 4-2b. `settlement_line` — 정산 원장 (append-only, 부호 amount, 1건=1사실)

```sql
CREATE TABLE settlement_line (
    line_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seller_id        BIGINT       NOT NULL,
    entry_type       TEXT         NOT NULL,               -- 아래 enum
    source_type      TEXT         NOT NULL,               -- ORDER_ITEM / PAYMENT_CANCEL / MANUAL / SELLER_DEPOSIT
    source_id        BIGINT       NOT NULL,               -- entry_type별 결정론적 PK(아래 표)
    source_event_id  TEXT         NOT NULL,               -- ★ 발행측 불변 고유 이벤트 ID(멱등의 진짜 기준)
    amount_minor     BIGINT       NOT NULL,               -- 부호 있음(아래 ck_settline_sign)
    currency         CHAR(3)      NOT NULL DEFAULT 'KRW' CHECK (currency ~ '^[A-Z]{3}$'),
    commission_base_minor BIGINT,                         -- COMMISSION 행: 수수료 기준액(=gross − seller_funded_discount)
    commission_rate  NUMERIC(7,6),                        -- 적용 율 스냅샷(COMMISSION 행)
    eligible_at      TIMESTAMPTZ  NOT NULL,               -- 구매확정 시각(이 시점부터 정산 대상)
    cycle_id         BIGINT       REFERENCES settlement_cycle(cycle_id) ON DELETE RESTRICT, -- 마감 시 귀속
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_settline_type CHECK (entry_type IN
        ('SALE','COMMISSION','REFUND','REFUND_COMMISSION','RETURN_SHIPPING_FEE',
         'PG_FEE_NONREFUND','ADJUSTMENT','REPAYMENT')),
    CONSTRAINT ck_settline_sign CHECK (                   -- ★ entry_type별 부호 정합(0원·부호플립 1차 차단)
        (entry_type = 'SALE'                AND amount_minor > 0) OR
        (entry_type = 'COMMISSION'          AND amount_minor < 0) OR
        (entry_type = 'REFUND'              AND amount_minor < 0) OR
        (entry_type = 'REFUND_COMMISSION'   AND amount_minor > 0) OR  -- 마켓플레이스 판매수수료 환원(+)
        (entry_type = 'RETURN_SHIPPING_FEE' AND amount_minor < 0) OR
        (entry_type = 'PG_FEE_NONREFUND'    AND amount_minor < 0) OR
        (entry_type = 'REPAYMENT'           AND amount_minor > 0) OR  -- 셀러 직접입금
        (entry_type = 'ADJUSTMENT'          AND amount_minor <> 0)),
    CONSTRAINT uq_settline_event UNIQUE (source_event_id)  -- ★ at-least-once 흡수(같은 이벤트 재INSERT는 23505→정상 swallow)
);
CREATE UNIQUE INDEX uq_settline_sale ON settlement_line (source_type, source_id) WHERE entry_type = 'SALE';        -- source 단위 SALE 유일
CREATE UNIQUE INDEX uq_settline_comm ON settlement_line (source_type, source_id) WHERE entry_type = 'COMMISSION';
CREATE INDEX ix_settline_seller_cycle ON settlement_line (seller_id, cycle_id);
CREATE INDEX ix_settline_eligible     ON settlement_line (eligible_at) WHERE cycle_id IS NULL;  -- 미배정분 스윕
```

- **부호 있는 append-only 원장** — 판매는 `SALE(+)` + `COMMISSION(−)` 두 줄, 환불은 `REFUND(−)`(+ 귀책·수단에 따라 `REFUND_COMMISSION(+)`/`RETURN_SHIPPING_FEE(−)`/`PG_FEE_NONREFUND(−)`)로 *새 줄 추가*합니다. 정정은 UPDATE가 아니라 반대부호 `ADJUSTMENT` 또는 `{source_event_id}:correction:N` 신규 행 + 원본 reversal(역분개).
- **`ck_settline_sign`이 load-bearing** — 선형 SUM 모델은 부호가 양변에서 함께 뒤집히면 상쇄돼 통과하므로, 부호 정합은 SUM이 아니라 **INSERT CHECK**로 막습니다(§10의 "1원 드리프트 봉쇄"의 DB 강제 지점).
- **멱등 = `source_event_id` UNIQUE** — `idempotency_key` 문자열보다 발행측 불변 고유 ID가 정확합니다. 같은 `source_id`에 다른 키로 SALE이 두 줄 드는 것은 `uq_settline_sale`이 막습니다.
- **`source_id` 결정론 규정**:

| entry_type | source_type | source_id |
|---|---|---|
| SALE / COMMISSION | ORDER_ITEM | `order_item.line_id` |
| REFUND / REFUND_COMMISSION / RETURN_SHIPPING_FEE / PG_FEE_NONREFUND | PAYMENT_CANCEL | `payment_cancel.cancel_id`(검수 분할이면 `return_item.id` 합성) |
| ADJUSTMENT | MANUAL | 정정 대상 `line_id` |
| REPAYMENT | SELLER_DEPOSIT | 입금 트랜잭션 id |

> **음수 부호 컨벤션 트레이드오프** — 복식부기 `ledger_entries`([`07`](./07-ledger-double-entry.md))는 방향을 `side(D/C)` + 양수로 표현하고 음수 금액을 금합니다. `settlement_line`은 "순지급 = SUM"이라는 **단일 축**이라 부호 있는 `amount`를 씁니다 — 부호 금지 원칙은 복식부기 원장 한정이고, 여기선 `ck_settline_sign`이 진짜 방어선입니다. (대안: `amount`를 항상 양수로 두고 집계 시 `CASE`로 부호화. 본 문서는 부호 amount + INSERT CHECK를 택함.)

### 4-3. `settlement`(집계 캐시) · `payout`(지급대행) · `payout_event`

```sql
CREATE TABLE settlement (                                 -- 셀러×주기×통화 정산서(집계 캐시; 권위는 settlement_line)
    settlement_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cycle_id           BIGINT NOT NULL REFERENCES settlement_cycle(cycle_id) ON DELETE RESTRICT,
    seller_id          BIGINT NOT NULL,
    currency           CHAR(3) NOT NULL DEFAULT 'KRW' CHECK (currency ~ '^[A-Z]{3}$'),
    gross_sales_minor  BIGINT NOT NULL DEFAULT 0 CHECK (gross_sales_minor >= 0),  -- 원장은 음수저장, 집계는 절댓값화
    commission_minor   BIGINT NOT NULL DEFAULT 0 CHECK (commission_minor >= 0),
    refund_minor       BIGINT NOT NULL DEFAULT 0 CHECK (refund_minor >= 0),
    net_payable_minor  BIGINT NOT NULL,                   -- 음수 가능(미수금) → >=0 안 검사
    status             TEXT   NOT NULL DEFAULT 'DRAFT',   -- DRAFT→CONFIRMED→PAID(+CARRIED_OVER)
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_settlement UNIQUE (cycle_id, seller_id, currency),
    CONSTRAINT ck_settlement_status CHECK (status IN ('DRAFT','CONFIRMED','PAID','CARRIED_OVER')),
    CONSTRAINT ck_settlement_net CHECK (                  -- 캐시 자기-정합(원장 SUM 일치는 주기 대사가 담당, 2계층)
        net_payable_minor = gross_sales_minor - commission_minor - refund_minor)
);

CREATE TABLE payout (                                     -- 실제 송금(토스페이먼츠 지급대행)
    payout_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_id   BIGINT NOT NULL REFERENCES settlement(settlement_id) ON DELETE RESTRICT,
    amount_minor    BIGINT NOT NULL CHECK (amount_minor >= 0),  -- 음수는 carryover로 이월 → payout은 net>=0일 때만 INSERT
    currency        CHAR(3) NOT NULL DEFAULT 'KRW' CHECK (currency ~ '^[A-Z]{3}$'),
    status          TEXT   NOT NULL DEFAULT 'REQUESTED',  -- ★ 토스 실값: REQUESTED→IN_PROGRESS→COMPLETED/FAILED(REQUESTED만 CANCELED)
    bank_account    JSONB  NOT NULL,                      -- 셀러 정산계좌 스냅샷
    idempotency_key TEXT   NOT NULL,                      -- 지급대행 멱등(이중송금 방지)
    completed_at    TIMESTAMPTZ,                          -- 송금 완료 시각(과거 paid_at)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_payout_status CHECK (status IN ('REQUESTED','IN_PROGRESS','COMPLETED','FAILED','CANCELED')),
    CONSTRAINT uq_payout_idem UNIQUE (idempotency_key)
);

CREATE TABLE payout_event (                               -- 송금 상태 이력(append-only) — 비동기 FAILED·재시도·웹훅 원문
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payout_id   BIGINT      NOT NULL REFERENCES payout(payout_id) ON DELETE RESTRICT,
    from_status TEXT, to_status TEXT NOT NULL,
    most_recent BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_key    INT         NOT NULL,
    payout_raw  JSONB,                                    -- 지급대행 응답/웹훅(payout.changed) 원문
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payoutevent_sortkey UNIQUE (payout_id, sort_key)
);
CREATE UNIQUE INDEX uq_payoutevent_most_recent ON payout_event (payout_id) WHERE most_recent;
```

- **`payout.status`를 토스 지급대행 실값으로 정렬** — 앞 판본의 `PAID`는 토스에 없는 값이라 `COMPLETED`로 교체했고, **`IN_PROGRESS`(배치 처리중)** 가 곧 §6의 **UNKNOWN 자금 버킷**입니다: 송금 후 타임아웃이면 `FAILED`로 단정하지 말고 `IN_PROGRESS`로 두고 `payout.changed` 웹훅 또는 지급조회 API로 확정하며, `IN_PROGRESS` 동안 같은 `idempotency_key` 외 신규 재송금을 금지합니다. 취소는 `REQUESTED`에서만 가능.
- **`settlement.PAID`는 payout `COMPLETED` 수신 후 마킹** — 내부 정산서 상태(`PAID`)와 외부 송금 상태(`COMPLETED`)를 구분(`SETTLED/DISBURSED`로 개명은 선택).
- **payout 게이트** — `settlement.status=CONFIRMED` AND 셀러 KYC `APPROVED`(또는 한도 내 `PARTIALLY_APPROVED`) AND 미수금 없음 AND 활성 SELLER 보류 없음(§4-5·§5).

### 4-4. 정산의 두 다리 — PG 입금 대사 (4-way 대사)

정산은 두 다리입니다 — ① 구매자→PG→플랫폼 **입금**(PG가 수수료 떼고 정산파일로 입금) ② 플랫폼→셀러 **송금**(payout). 위 §4-1~4-3은 ②만 다뤘으므로 ①을 추가합니다. **이게 없으면 "PG가 실제로 안 줬는데 셀러에게 먼저 지급"하는 사고를 막을 데이터 근거가 없습니다.**

- **PG 입금 대사 4-way** — [`SCHEMA.md`](./SCHEMA.md)의 `pg_settlement_raw`(PG 정산파일 staging)·`recon_matches`(원장↔입금 건별)·`recon_transitions`를 끌어와: `settlement_line ↔ pg_settlement_raw ↔ 복식부기 원장(CLEARING_PG 잔액=0 불변식) ↔ 은행입금`을 대조합니다([`08`](./08-settlement-and-reconciliation.md)).
- **미수→입금 전이** — confirm 시 SALE 차변이 `CLEARING_PG`(미수)로 떨어졌다가 PG 입금파일 매칭 시 `PLATFORM_CASH`로 청산됩니다. **`CLEARING_PG` 비0 잔액 = reconciliation 알람.**
- **PG 실수수료 vs 기대값** — `pg_settlement_raw.fee_minor`와 우리 기대 `pg_fee`를 건별 대조해 PG 정산 누락·차액을 탐지합니다.
- **pending/available 분리(토스 잔액 모델)** — `settlement_line`의 `(cycle_id NULL & eligible_at 미도래)` = **pending**(토스 `pendingAmount`, 아직 지급 불가), `(주기 귀속 CLOSED)` = **available**(`availableAmount`, 주기 도래 전환). 셀러 잔액 read model에 `pending/available_amount_minor`로 분해 노출합니다. **이 전환이 "왜 구매확정 후 정산하나"의 원리 자체**입니다("지급한 정산금은 회수하기 어렵다").

### 4-5. 마이너스 정산과 미수금 — carryover · 자동상계 · 미수금 게이트

구매확정 후에도 환불은 발생하고, 이미 지급된 건이 환불되면 셀러 정산이 음수가 됩니다(미수금). **음수 잔액을 1급으로 모델링**합니다([`SCHEMA.md`](./SCHEMA.md) `settlement_periods.carryover_in/out_minor` 동형).

- **이월** — 마감 시 `net = carryover_in + SUM(settlement_line)`. `net < 0`이면 `carryover_out = net`으로 다음 OPEN 주기 `carryover_in`에 이월하고, **payout은 `net ≥ 0`일 때만** 생성(`amount_minor >= 0` CHECK 유지). `settlement.status`에 `CARRIED_OVER`.
- **해소 순서(동급 OR 아님)** — ① **차기 정산 양수액과 자동 상계**(1순위 기본) → ② 셀러 직접입금/청구(잔액이 지속 음수일 때 보조). 셀러 직접입금은 `settlement_line.entry_type='REPAYMENT(+)'`로 원장에 1급 표현. (병행 가능하므로 단일 순서로 단정하지 않음 — 토스 공식.)
- **미수금 게이트** — `has_outstanding_balance` 셀러는 신규 payout 차단·정산계좌 변경 불가·한도 증액 불가(`seller_id` 단위, 멀티셀러 분할 정합).
- carryover(사후 이월)와 reserve/롤링리저브(사전 완충, §10 비율유보)는 **다른 개념**입니다 — reserve는 의도적 코어 밖.

### 4-6. 정산 라인의 세금·배송비·부담주체 분해 (구성요소 모델)

위 §4-2b의 5종 `entry_type`은 부가세·배송비·셀러부담/플랫폼부담 할인이 `net`·`commission_base`에 반영되지 않는 **단순화 모델**입니다. `SCHEMA-toss`가 이미 `order_payment.supplied_amount/vat`·`order_item.taxable_base`를 들고 있으므로, 정밀 정산이 필요하면 [`SCHEMA.md`](./SCHEMA.md) `settlement_lines`의 **구성요소 모델**로 승격합니다.

- 구성요소: `gross_amount / seller_funded_discount / platform_funded_discount / tax / shipping_fee / commission_base / commission_amount / pg_fee / net_payable`.
- 두 항등식 CHECK(SCHEMA.md): `ck_settle_net`(net = gross − commission − tax − seller_funded + shipping_fee), `commission_base = gross − seller_funded_discount`. **셀러부담 할인은 수수료 기준에서 빼고, 플랫폼부담 할인은 안 뺍니다.** 수수료를 `gross`가 아니라 `commission_base`에 곱해야 과대계산(셀러 소송 직결)을 막습니다 — 이것이 §4-2b의 `commission_base_minor`가 load-bearing인 이유.
- 부가세 공급시기(카드=결제일, 무통장=배송/구매확정일)는 `eligible_at`과 **별개 타임스탬프**. 세금계산서 귀속(셀러→구매자 위수탁판매, 플랫폼→셀러 수수료분, PG 역발행)은 계약 의존이라 단서만 둡니다. PG 결제수수료(`pg_fee`)의 본 코어 미수용은 의도적 단순화이며 [`08`](./08-settlement-and-reconciliation.md) §8.4로 위임.

---

## 5. 도메인 간 연결 — 무엇을 동기로, 무엇을 이벤트로

dual-write(내 DB + 메시지)를 한 트랜잭션으로 못 묶으므로 **아웃박스 패턴**으로 잇습니다. 발행 방식(폴링 릴레이 vs CDC/Debezium·슬롯 lag)·미발행 재발행 스윕·poison 격리는 [`09`](./09-outbox-saga-and-audit.md)·[`04-outbox-vs-2pc`](../flab-mentoring/2026-06-20/04-outbox-vs-2pc.md)에 상세합니다.

<details>
<summary>🧩 <b>"왜 dual-write를 한 트랜잭션으로 못 묶나 — 그리고 이건 DDD 경계 문제가 *아니다*" (개념 분리)</b></summary>

먼저 흔한 혼동부터 끊는다 — **두 개의 다른 질문이 섞여 있다.**

```
[질문 A] "왜 이벤트로 통신하나?"          ← DDD 경계의 문제 (WHY)
[질문 B] "그 이벤트를 어떻게 안 잃고        ← 분산 시스템 원자성 문제 (HOW)
         발행하나? = dual-write 문제"
```

**dual-write를 한 트랜잭션으로 못 묶는 이유는 DDD 경계·insert 권한과 무관하다.** 순수하게 *"DB와 메시지 브로커는 서로 다른 시스템이라, 하나의 DB 트랜잭션이 둘을 못 덮는다"*는 분산 시스템의 근본 한계다.

**왜 못 묶나 — 트랜잭션은 "하나의 리소스" 안에서만 원자적이라서.** `BEGIN…COMMIT`의 ACID는 그 DB 한 대 안에서만 보장된다. DB의 `COMMIT`은 Kafka에게 "너도 같이 커밋해"라고 명령할 권한이 없다(서로 모르는 독립 시스템).

```
   하나의 "결제 완료"가 두 시스템에 써야 함:
   ┌─────────────┐         ┌──────────────────┐
   │ PostgreSQL   │         │ Kafka / RabbitMQ │   ← 다른 프로세스·다른 네트워크
   │ order_event  │         │ OrderPaid 발행    │
   └─────────────┘         └──────────────────┘
        ↑ DB 트랜잭션              ↑ DB 트랜잭션이
          여기까진 보장              여기는 못 미침
```

**그래서 생기는 부분 실패(어느 순서로 해도 갈라짐):**
```
DB 먼저 → 메시지 나중:  order_event COMMIT ✅ → OrderPaid 발행 💥(앱 죽음/브로커 장애)
   결과: DB는 "결제됨"인데 배송·정산은 영영 모름 → 출고 안 됨, 정산 누락 (유실)
메시지 먼저 → DB 나중:  OrderPaid 발행 ✅ → order_event INSERT 💥(롤백)
   결과: 배송이 출고했는데 주문 DB엔 결제 기록 없음 (유령 이벤트)
```
분산 시스템에서 장애는 "혹시"가 아니라 "반드시 언젠가"다(OOM·배포 재시작·브로커 리밸런싱·네트워크 단절).

**이게 DDD 경계 문제가 아닌 증거(반례):**
- DDD 경계가 있어도 **동기 호출**(같은 트랜잭션 내 application service)이면 외부 브로커가 없어 dual-write 문제가 *없다*(§5 표에서 돈·재고를 동기로 두는 이유).
- DDD를 안 쓰는 **한 덩어리 모놀리스**라도 "주문 저장 + Kafka 발행"이면 dual-write 문제가 *있다*.

**진짜 트리거 = "한 트랜잭션이 못 덮는 *두 번째 리소스*"가 등장하는가.** 그 두 번째 리소스(브로커·다른 DB·원격 서버)가 끼는 순간에만 생긴다.

| 구성 | 두 번째 리소스? | dual-write? |
|---|---|---|
| 모놀리식·같은 DB·**동기 메서드 호출**·단일 TX | 없음 | ❌ **없음** |
| 모놀리식·같은 DB·근데 **외부 브로커로 이벤트 발행** | 브로커 | ✅ 있음 → 아웃박스 |
| 모놀리식·도메인마다 **다른 DB** | 다른 DB | ✅ 있음(또는 2PC) |
| MSA·**HTTP 동기 호출** | 원격 서버 | (dual-write는 아니나) 분산 호출 실패 → saga |
| MSA·**비동기 이벤트** | 브로커 | ✅ 있음 → 아웃박스 |

> **"동기"라고 다 안전한 게 아니다 — "같은 프로세스·같은 트랜잭션"이라야 안전하다.** in-process 메서드 호출(`shipmentService.create(...)`)은 같은 TX라 dual-write 없음. 하지만 HTTP REST로 *다른 서버*를 동기 호출하면 내 DB TX에 못 들어가 어긋남(이건 dual-write가 아니라 분산 호출 실패 → saga).

**그리고 DDD ≠ 마이크로서비스 — 가장 흔한 오해.** DDD는 *논리적 경계*(자기 데이터 소유·남의 테이블 직접 조인 금지·식별자 참조)지 *물리적 분리*(서버·DB·브로커)가 아니다. **한 서버·한 DB·한 프로세스 안에서도 완벽히 DDD를 한다**(modular monolith). 물리 분리는 장애격리·독립배포·독립스케일이 *실제로* 필요할 때의 별도 결정이고, 그때 비로소 dual-write가 따라온다.

```
   진화 경로 (대부분 이렇게 감):
   ① 모듈러 모놀리스          경계 O, 물리 분리 X
      (DDD 경계 + 동기 호출)   → dual-write 없음. 가장 단순. 여기서 시작.
            │ (장애격리·독립배포가 실제로 필요해지면)
            ▼
   ② 서비스 분리 + 이벤트      경계 O, 물리 분리 O
      (outbox + 브로커)       → dual-write 등장 → 아웃박스로 해결
```
이 문서가 이벤트·아웃박스를 말하는 건 **②단계를 전제**했기 때문이다. 그래서 §5 표가 *경계마다* 다르게 건다 — 돈·재고는 즉시 일관성이 필수라 **동기(①식 한 TX)**로 남기고, 배송·정산은 장애격리가 중요하고 즉시일 필요 없어 **이벤트(②식)**로 푼다. (참고로 순수 DDD엔 "한 트랜잭션은 한 aggregate만 수정" 원칙이 있어, ①에서 주문·배송을 한 TX로 묶는 건 실용적 타협이다 — 이 긴장이 "동기로 묶을까/이벤트로 풀까"의 또 다른 축.)

> 한 줄: **dual-write ⟺ 한 트랜잭션이 못 덮는 두 번째 리소스(외부 브로커·다른 DB·원격 서버). dual-write ⟺ DDD 경계가 아니다.** 모놀리식·같은 DB·동기 호출이면 경계를 그어도 dual-write가 없고, DDD는 서버 분리를 요구하지 않는다(경계는 항상, 물리 분리·비동기는 필요할 때). (상대 도메인 Read 가능 여부·FK≠JOIN은 §3-1 토글 참조 — "FK는 존재 무결성, JOIN은 상태 읽기"라 배송은 `Long orderId` 값만 들고 자기 테이블만 읽는다.)

</details>

<details>
<summary>⚖️ <b>"아웃박스만이 답인가?" — 분산 원자성 해법 5종 비교</b></summary>

아니다. 본질은 **"두 시스템에 원자적으로 못 쓴다 → 하나에만 쓰고 나머지를 거기서 파생시킨다"**이고, 그 길은 여럿이다.

| 해법 | 원리 | 트레이드오프 |
|---|---|---|
| **2PC (XA 분산 트랜잭션)** | 조정자가 "준비됐나?→다 같이 커밋" 2단계 합의 | 이론상 정답. 단 blocking·코디네이터 단일장애점·성능↓·**Kafka XA 미지원** → 실무 기피(아래 토글) |
| **아웃박스(Outbox)** | 메시지를 *같은 DB의* `outbox`에 **DB 트랜잭션 안에서 함께 INSERT**, 릴레이가 읽어 발행 | dual-write가 "한 DB 두 테이블 INSERT"로 바뀜 → 원자화. 대가는 최종 일관성·at-least-once(중복) |
| **CDC (Debezium)** | 테이블 변경을 **DB의 WAL/binlog**에서 직접 읽어 발행, 앱이 발행 코드를 안 짬 | 폴링 부하 없음·누락 거의 없음. 대가는 Debezium/Connect 운영·복제 슬롯 lag 관리 |
| **Listen-to-yourself** | 브로커에만 발행하고, 자기가 다시 구독해 DB에 씀 | DB가 진실의 원천이 아니게 됨 → **돈·정산엔 부적합** |
| **이벤트 소싱** | 애초에 "상태"가 아니라 "이벤트"만 저장 → 발행할 게 곧 저장된 것 | 강력하나 패러다임 전환 비용 큼 |

**이 시스템이 아웃박스/CDC를 고른 이유** — 2PC는 Kafka가 못 받고 돈 경로 긴 락이 치명적이라 탈락. 아웃박스는 `order_event` INSERT와 `outbox` INSERT가 *같은 DB·같은 트랜잭션*이라 원자성이 공짜다.

```
   ┌──────────── 한 DB, 한 트랜잭션 ────────────┐
   │  INSERT order_event (PAID)                  │  둘 다 성공
   │  INSERT outbox (type=OrderPaid, payload)    │  or 둘 다 롤백
   │  COMMIT  ← 원자적                            │
   └─────────────────────────────────────────────┘
              │ (커밋 후) 릴레이/CDC가 outbox 읽어 → Kafka 발행 (실패 시 재시도, 행이 남아 안 잃음)
```

> **대가 = at-least-once(중복).** 릴레이가 발행 후 ack 전에 죽으면 같은 행을 또 발행한다. 그래서 §5 하단 **멱등(`source_event_id`)·순서(파티션키)·역전이(상태머신 가드) 3종**이 필수가 된다. **아웃박스는 "유실"을 없앤 대가로 "중복·순서"를 떠안고 소비자 멱등으로 받는 구조다.**

</details>

<details>
<summary>🤝 <b>2PC(투페이즈 커밋)와 XA가 정확히 뭐고, 왜 실무에서 기피하나</b></summary>

**2PC = 개념, XA = 그 표준 규격.** 2PC는 "바로 커밋 말고, 먼저 전원에게 *준비됐냐* 묻고 전원 YES면 다 같이 커밋"하는 프로토콜이다(결혼식 주례 — 둘 다 "예" 해야 성혼 선포). **XA**는 그 2PC를 *서로 다른 벤더 제품이 호환되게* 만든 산업 표준(X/Open, 1991)이다.

```
   2PC(개념) ──표준화──▶ XA(규격) ──구현──▶ Oracle·PostgreSQL·MySQL·IBM MQ …
```

**두 역할:**
```
   TM (Transaction Manager) = 조정자.  PREPARE/COMMIT 명령. (Java=JTA, Atomikos·Narayana)
   RM (Resource Manager)    = 참가자.  실제 데이터 가진 시스템(DB·브로커). XADataSource/XAResource 구현
```

**2단계:**
```
 Phase 1 PREPARE(투표): TM "준비됐나?" → 각 RM 변경을 쓰되 미확정 + "YES(반드시 커밋 가능)" 약속
 Phase 2 COMMIT(완료):  전원 YES → "다 커밋!"  /  하나라도 NO·무응답 → "다 롤백!"
```

**왜 기피하나(4가지):**
1. **Blocking** — "YES" 한 RM은 Phase 2 신호가 올 때까지 **락을 쥔 채 대기**. 돈 경로에 긴 락은 처리량 폭락.
2. **코디네이터 단일장애점(최악)** — 전원 YES 직후 TM이 죽으면, RM들은 약속 때문에 롤백도 못 하고 **in-doubt(미결)** 상태로 락을 쥔 채 영원히 대기. "2PC는 blocking protocol" 악명의 핵심.
3. **성능** — 매 트랜잭션이 PREPARE+COMMIT 2번 왕복 + 단계마다 fsync.
4. **Kafka XA 미지원(결정타)** — RM이 되려면 `xa_prepare/xa_commit`을 구현해야 하는데, RDB는 하지만 **Kafka는 XA 인터페이스를 제공하지 않는다.** 그래서 "DB+Kafka"는 XA 트랜잭션으로 **묶을 수조차 없다.**

```
   2PC가 막는 것: 부분 실패(원자성)      ✅ 이론상 해결
   2PC가 가져오는 것: blocking·단일장애점  ❌ CAP의 가용성을 희생
                     ·느림·Kafka 미지원
```

> 한 줄: **2PC/XA는 분산 원자성을 정공법으로 풀지만 blocking·코디네이터 단일장애점·성능, 그리고 Kafka XA 미지원으로 'DB+브로커' 조합에선 못 쓴다. 그래서 분산 트랜잭션을 강제하는 대신, DB 한 곳에만 쓰고 메시지를 파생시키는 아웃박스(최종 일관성+멱등)가 실무 정설이 됐다.** (XA가 성립하는 곳은 DB↔DB나 DB↔전통 JMS이고, 그조차 MSA에선 점점 안 쓴다.)

</details>

| 경계 | 동기 / 비동기 | 왜 |
|---|---|---|
| 결제 confirm | **동기** | 사용자가 결과를 즉시 봐야 함. 멱등키+재조회로 안전 |
| 재고 차감/예약·확정 | **동기**(주문·결제 트랜잭션 내) | oversell은 즉시 막아야 — 조건부 원자 UPDATE([`04`](./04-inventory.md)) |
| 주문 PAID → 배송 골격 생성 | **비동기**(OrderPaid→아웃박스) | 셀러 출고는 즉시일 필요 없음, 장애 격리 |
| 배송완료 → 구매확정 타이머 | **비동기**(스케줄러, 정책일) | 시간 기반(추적불가 fallback 포함) |
| 구매확정/환불 → 정산 적재 | **비동기**(이벤트→settlement_line append) | 정산은 배치 성격, 결합 분리 |
| **정산 보류/해제(hold/release)** | **동기**(운영 액션 즉시 반영) | 분쟁·KYC·계좌오류·미수금 시 즉시 마감 대상 제외(§4-5) |
| 정산 마감 → payout | **배치 + 동기 송금** | Spring Batch 성격(주기 집계·정산서·payout 산출). 라인 적재는 실시간이어도 마감은 대용량 배치 |

<details>
<summary>🧵 <b>이 표의 "동기/비동기"를 오해 없이 — 동기·비동기·트랜잭션·자원은 *4개의 다른 축*</b></summary>

표를 읽을 때 "동기=HTTP", "TX 밖=대기 안 함" 같은 묶음이 혼동을 만든다. **네 축을 분리**하면 표가 닫힌다.

```
   축 A  동기 vs 비동기   = "호출자가 응답을 기다리느냐(blocking)?"   ← 통신수단과 무관
   축 B  in-process vs 원격 = "같은 프로세스 메서드 호출이냐 HTTP/gRPC냐?"
   축 C  TX 안 vs TX 밖   = "DB 롤백 단위에 포함되느냐?"
   축 D  자원: DB 커넥션 vs 워커 스레드 = "무엇을 점유하느냐?"
```

**① 동기 ≠ HTTP.** 동기/비동기는 "기다리느냐"의 축이다. 이 문서는 modular monolith 전제(§1)라 표의 "동기"는 거의 다 **같은 프로세스 안 같은 트랜잭션으로 메서드를 직접 호출**(in-process, HTTP 아님, 이벤트 전파 없음)이다. HTTP 동기 호출(요청-응답)도 동기의 일종이고, 비동기는 "메시지 던지고 안 기다림"이다.

**② 동기에는 두 결이 있다.**
- **(a) 내부 DB = 같은 트랜잭션으로 묶음** — 재고 차감/예약·확정은 *각각이 아니라* 주문·결제와 **한 TX**(표의 "주문·결제 트랜잭션 내"). oversell을 즉시 막아야 하니 한 묶음이어야 하고, `outbox(OrderPaid)` INSERT도 같은 TX에 들어간다(dual-write 해소).
- **(b) 외부 시스템 = 동기로 기다리되 TX 밖 + 멱등키** — 결제 confirm의 PG 호출, payout 송금.

**③ 동기 ⊥ 트랜잭션 — PG confirm은 "동기 + TX 밖".**

| | TX 안 | TX 밖 |
|---|---|---|
| **동기**(기다림) | 재고 확정(내부 DB 메서드) | **PG confirm·payout 송금** |
| **비동기**(안 기다림) | outbox INSERT(저장만, 발행은 나중) | 이벤트 컨슈머(배송 생성, 별도 TX) |

PG 호출이 **동기**인 이유 — `tossClient.confirm()`이 승인/거절 응답을 줄 때까지 스레드가 멈춰 기다리고(blocking), 사용자도 결과를 즉시 봐야 한다. **TX 밖**인 이유 — ⓐ 외부는 DB ACID 밖이라 내 DB를 롤백해도 PG 승인은 *이미 일어나* 못 되돌린다, ⓑ 외부 I/O를 TX에 넣으면 DB 커넥션·락을 응답 시간 내내 점유한다(안티패턴).

```
   [TX-A] order_payment READY 선점(멱등키 UNIQUE) → COMMIT(커넥션 반납)
   [TX 밖] tossClient.confirm() ← 동기 대기. DB 커넥션 없음.  ← 동기지만 TX 밖
   [TX-B] DONE + 재고확정 + order_event(PAID) + outbox → COMMIT
   사이 장애 → UNKNOWN → 멱등키(이중승인 방지) + 재조회(토스 Query로 실제상태 확정)
```

**④ TX 밖이어도 "DB 커넥션"과 "스레드"는 다르게 점유된다.**
```
   PG 응답 대기 중:
     DB 커넥션(HikariCP, 희소 10~50) → TX 닫았으니 *반납됨* ✅  ← TX 밖에 두는 진짜 목적
     워커 스레드(Tomcat, 수백)        → 동기 blocking이라 *계속 점유* ⚠️
```
한 TX로 묶으면 DB 커넥션이 PG 응답 시간 내내 잡혀 **풀 고갈 → 전체 API 마비**(동시 결제 50건=커넥션 50개 묶임). TX 밖이면 커넥션은 짧게 두 번만 잡고 PG 대기 동안엔 안 잡는다. 남는 **스레드 점유**는 별도 계층 — **타임아웃·서킷 브레이커·벌크헤드**, 더 가면 **논블로킹/가상 스레드**로 막는다(이때도 결제는 *논리적으론* 동기, 기다리는 *방식*만 바뀜).

**표 각 행 분류:**

| 경계 | 통신 실체 | 트랜잭션 |
|---|---|---|
| 결제 confirm | 동기, 내부 in-process / PG 외부 호출(기다림) | 내부 DB 한 TX, **PG 호출만 TX 밖**(멱등키+재조회) |
| 재고 차감/예약·확정 | 동기, in-process 메서드 호출 | **주문·결제와 하나의 TX**(별도 X) |
| 주문 PAID → 배송 골격 | 비동기, 아웃박스 이벤트 | outbox는 결제 TX 내 INSERT, 배송 생성은 **별도 TX** |
| 배송완료 → 구매확정 타이머 | 비동기, 스케줄러(시간 기반) | 스케줄러 별도 TX |
| 구매확정/환불 → 정산 적재 | 비동기, 이벤트 | settlement_line append **별도 TX** |
| 정산 hold/release | 동기, in-process(운영 액션) | 내부 DB 한 TX |
| 정산 마감 → payout | 배치 + payout 외부 동기 호출 | 집계 배치 TX, **payout 송금 TX 밖**(멱등키, 토스 지급대행) |

> 한 줄: **동기/비동기(기다림)·통신수단(in-process/HTTP)·트랜잭션(안/밖)·자원(커넥션/스레드)은 4개의 독립 축이다. 표의 "동기"는 대부분 같은 프로세스 같은 TX 메서드 호출(HTTP 아님), 재고는 주문·결제와 한 TX로 묶이고, PG·payout은 "동기+TX밖+멱등키"다. TX 밖의 목적은 희소한 DB 커넥션 조기 반납이고, 남는 스레드 점유는 타임아웃·서킷브레이커·벌크헤드로 막는다.**

</details>

> **이벤트 멱등 + 순서 + 역전이는 다른 3가지** — 이벤트는 at-least-once라 **멱등키(`source_event_id`)는 중복만** 흡수합니다. **순서**(PAID 이전 REFUNDED·DELIVERED 역전)는 멱등으로 못 막으므로, 상태머신이 있는 **주문계**(`order_id` 파티션)는 같은 파티션 순차 처리로 잡습니다. 다만 **정산계는 다릅니다** — `settlement_line`이 부호 있는 append-only 원장이고 잔액은 `SUM(amount_minor)`라 **덧셈이 교환법칙적**이므로, REFUND가 SALE보다 먼저 적재돼도 최종 잔액은 동일합니다. 즉 정산계는 *순서 보장이 불필요*하고 **멱등만으로 순서 무관 병렬 처리**가 가능합니다. **역전이**의 진짜 위험("SALE 확정 전 음수 지급")은 적재 순서 가드가 아니라 **payout 게이트**(`net ≥ 0` AND `CONFIRMED`)와 `carryover`(§4-5)가 책임집니다. 상세는 [`09`](./09-outbox-saga-and-audit.md) §9.3/§9.4. 토스 운영장치: 아웃박스 헤더에 멱등키를 실어 헤더만으로 dedup, RO 복제본 매시 검증 배치로 복제지연 누락분 재적재([토스 legacy-5](https://toss.tech/article/payments-legacy-5)).

<details>
<summary>🔁 <b>"파티션 키가 도메인 경계(order_id→seller_id)에서 바뀌면 순서 역전 안 나나?" — 부호 원장이 순서 문제를 *녹인다* + 고아 REFUND</b></summary>

**질문의 함정**: 주문계는 `order_id`, 정산계는 `seller_id` 파티션이라, 경계를 넘는 순간 전역 순서가 흩어진다 — 정산 도메인에 **REFUND가 대응 SALE보다 먼저 도착**할 수 있다. "그럼 순서 역전 아니냐"가 자연스러운 추궁이다.

**핵심 통찰 — 순서는 애초에 문제가 아니다.** 잔액을 `balance = balance − 5000` 같은 **단일 컬럼 UPDATE**로 관리했다면 순서가 치명적이다(SALE 전 음수 검증·이력이 깨짐). 그러나 우리는 **부호 append-only 원장 + `SUM` 도출**을 택했다:

```
SUM(SALE +10000, COMMISSION −1000, REFUND −5000)
  = SUM(REFUND −5000, SALE +10000, COMMISSION −1000)   ← 도착 순서 무관, 결과 동일
```

**설계 선택이 문제를 녹인 사례**다. 따라서 정산계는 `seller_id` 단일 컨슈머 순차 처리로 처리량을 깎을 이유가 없다 — **멱등(`source_event_id` UNIQUE)만 있으면 병렬·순서 무관**.

**그럼 진짜 문제는?** "라인 존재 순서"가 아니라 **"확정 안 된 매출에서 음수가 실제 *송금*되는 것"** — 즉 *payout 게이트* 문제로 축소된다. SALE 없이 REFUND가 먼저 와도:
- REFUND를 **즉시 음수 라인으로 적재**(원장은 사실을 거부하지 않음). 잔액 일시 음수 가능.
- 안전은 **payout 게이트**(`net ≥ 0` AND `CONFIRMED` AND 미수금 없음, §4-3·§4-5)가 책임 — 음수면 `amount_minor >= 0` CHECK로 payout INSERT 자체가 안 되고 `carryover`로 이월. **버퍼링도, 거부+재시도도 불필요**(둘 다 상태 표면을 늘리거나 무한 재시도/DLQ 폭증을 부른다).
- pending/available 분리(§4-4)와 결합: 대응 SALE이 아직 pending이면 음수 REFUND도 pending 버킷에 머물러 **available(인출 가능액)에 정체불명 음수가 안 뜬다**.

**고아 REFUND(영원히 SALE이 안 옴)** — 두 원인을 구분:
- ① **SALE 존재하나 이벤트 유실** → RO 복제본 매시 검증 배치로 누락분 재적재(음수는 일시적, 자연 해소).
- ② **애초에 정산 비대상**(구매확정 전 취소 등)인데 REFUND가 잘못 흘러옴 → 상류 결함. "참조 주문이 정산 대상인가" 게이트로 걸러 **격리 큐(quarantine)**로 보내 사람 검토.
- 타임아웃: 음수가 N일(정산주기+안전마진) 이상 대응 SALE 없이 남으면 **고아 알람 + 격리**. 자동 0 삭제 금지(돈 사실 임의 삭제 = 회계 붕괴) — 반드시 `ADJUSTMENT`(역분개) + 사람 개입으로 해소.

</details>

### 정산 보류 — `settlement_hold` (append-only)

분쟁·KYC 미완료·정산계좌 오류·미수금으로 특정 라인/셀러 지급을 hold/release하는 일이 빈번한데, 현재는 `cycle_id`가 채워지면 곧 지급 대상이라 "구매확정됐지만 보류"를 표현할 길이 없어 1급 메커니즘을 둡니다.

```sql
CREATE TABLE settlement_hold (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope       TEXT NOT NULL CHECK (scope IN ('LINE','SELLER')),
    line_id     BIGINT REFERENCES settlement_line(line_id),       -- scope=LINE일 때
    seller_id   BIGINT NOT NULL,
    hold_reason TEXT NOT NULL CHECK (hold_reason IN ('DISPUTE','KYC_REQUIRED','ACCOUNT_ERROR','NEGATIVE_BALANCE','MANUAL')),
    hold_status TEXT NOT NULL DEFAULT 'HELD' CHECK (hold_status IN ('HELD','RELEASED')),  -- 정정은 새 행(most_recent)
    actor_id    BIGINT, note TEXT,
    sort_key    INT  NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 마감 조건: eligible_at < cutoff AND 활성 LINE 보류 없음 AND 셀러 활성 SELLER 보류 없음.
-- 보류 라인은 cycle_id 미배정으로 남겨 RELEASED 후 차기 주기 자연 편입.
```

---

## 6. 핵심 플로우 단계별

**구매(happy path).** ① 주문 생성: `orders`+`order_item` INSERT, 재고 **예약**(B패턴, [`SCHEMA.md`](./SCHEMA.md) `reservations`). ② 결제: `order_payment`(READY, idem 선점) → 토스 confirm → DONE → `order_event(PAID)`. ③ 재고 **확정**(RESERVED→CONFIRMED), `OrderPaid` 아웃박스 발행. ④ 배송: `OrderPaid` 소비 시 **시스템이 셀러별 `shipment`(READY) 골격을 자동 생성**(멀티셀러면 셀러 수만큼) → 셀러가 `POST /shipments`(또는 `PATCH …:dispatch`)로 `carrier_code`·`tracking_no`만 채우고 추적공급사에 등록 → `PICKED_UP` 전이. (소비자가 배송 단위를 만들지 않음 — 앞 판본 "소비자가 생성" 오기 정정.) ⑤ 배송완료(`DELIVERED`) → 자동확정 타이머(정책일·추적불가 fallback). ⑥ 구매확정 → `order_event(CONFIRMED)` → `PurchaseConfirmed` → `settlement_line`(SALE+, COMMISSION−) append. ⑦ 주기 마감 → `settlement` 집계 → `payout` 송금.

**취소·환불 — `shipment` 상태별 3분기.** (앞 판본은 §2 다이어그램과 §6/§3-4가 같은 부분취소를 다르게 그려 비정합 → 통일.)
- **(a) 미생성/READY**(출고 전) → 단순취소: 회수·검수 없이 즉시 `payment_cancel` append + 예약/확정 재고 복원 + `*→CANCELED`. (PICKED_UP 진입 전에만 CANCEL 허용 — 쿠팡 출고정지요청 대응.)
- **(b) PICKED_UP~IN_TRANSIT~OUT_FOR_DELIVERY**(배송중) → 단순취소 불가, `return_request(RETURN)`로만 회수 → 검수(**정상분만 복원**) → 결제취소 → 정산차감.
- **(c) DELIVERED** → 반품 경로 동일 + 자동확정 타이머 경합 가드(확정 전 반품 접수 시 타이머 정지/연장).
- DB 강제: 출고 후(SHIPPED/DELIVERED) 라인의 단순 CANCEL은 `shipment_item`으로 최신 배송상태를 조회해 조건부 UPDATE로 거부(불법 전이=0행).

**환불 수수료 환원 — `reason_type × 결제수단` 2축 조합.** (앞 판본은 "단순변심이면 REFUND_COMMISSION(+)"를 일률 적용해 부호가 틀어짐.) **마켓플레이스 판매수수료 환원**(`REFUND_COMMISSION`)과 **PG 결제수수료**(`pg_fee`)는 별개입니다.

| 케이스 | 정산 라인 조합 |
|---|---|
| 단순변심 + 카드 | `REFUND(−)` + `REFUND_COMMISSION(+)` (PG 수수료 카드는 취소 시 환원) |
| 단순변심 + 현금성(계좌이체·가상계좌) | `REFUND(−)` + `REFUND_COMMISSION(+)` + `PG_FEE_NONREFUND(−)` (최초 결제수수료 비환원) |
| 셀러귀책(파손·오배송) | `REFUND(−)` + `RETURN_SHIPPING_FEE(−)` + 판매수수료 **미환원** + 현금성이면 `PG_FEE_NONREFUND(−)` |

즉 **판매수수료 환원 = (귀책이 구매자측 단순변심)**, **PG수수료 환원 = (method가 카드)** 로 직교합니다. 환원율은 method별 정책 테이블 조회.

**멀티셀러 격리.** 결제(`order_payment`)는 주문 1건당 1개(구매자 관점), 배송·정산은 `seller_id`로 분할(`shipment`·`settlement_line`). 한 셀러 라인의 환불이 다른 셀러 정산을 안 건드리는 것은 `settlement_line`이 `source_id·seller_id`별 개별 행이라 **구조적으로 보장**됩니다. 분할 합 불변식(Σ 셀러별 SALE 라인 = 라인 net 매출 합)과 부담주체 split(셀러부담 할인을 `commission_base`에서 차감)은 [`SCHEMA.md`](./SCHEMA.md)의 `trg_discount_funding_split`(DEFERRABLE, 커밋 시 fail-closed)로 강제합니다.

**타임아웃·UNKNOWN.** 결제 confirm 타임아웃은 실패가 아니라 UNKNOWN → 토스 Query 재조회로 확정([`SCHEMA-toss`](./SCHEMA-toss.md) §10). **payout 송금 타임아웃도 동일** — `IN_PROGRESS`로 두고 `payout.changed` 웹훅/지급조회로 확정, 재조회 전 재송금 금지(§4-3). 워크스루는 [`01-append-only`](../flab-mentoring/2026-06-20/01-append-only-vs-state-update.md) §4 시나리오 B.

---

## 7. API 설계 (주요 엔드포인트)

| 도메인 | 메서드·경로 | 멱등 | 비고 |
|---|---|---|---|
| 주문 | `POST /orders` | `Idempotency-Key` 헤더 | 생성 시 재고 예약, `order_no` 발급 |
| 결제 | `POST /orders/{no}/payments/confirm` | order_payment.idempotency_key | 서버가 amount 검증 후 토스 confirm |
| 배송 | `POST /shipments`(or `PATCH /shipments/{id}:dispatch`) | — | 셀러 출고 등록 = READY 골격에 운송장 채움 + **추적공급사 register** |
| 배송 | `POST /webhooks/carrier` | `carrier_code+tracking_no`(+상태+시각) | 추적 콜백(register-then-callback, at-least-once) |
| 환불 | `POST /orders/{no}/returns` | return_request.idempotency_key | 반품/교환 접수 → 검수 후 취소 |
| 정산 | `GET /sellers/{id}/settlements?cycle=` | — | 셀러 정산서 조회(pending/available 분해, CQRS read model) |
| 정산 | `POST /admin/settlement-cycles/{id}/close` | cycle 상태 전이 | 주기 마감(OPEN→CLOSING→CLOSED, 집계→payout) |

- **외부 노출 식별자는 `order_no`(불투명)** — 내부 PK 비노출(enumeration 방어). 셀러 API는 `seller_id` 스코프 강제(IDOR 방지).

---

## 8. 동시성·멱등·정합 (기존 원칙의 적용)

- **재고 oversell** → 조건부 원자 UPDATE(0행=거부). 예약/확정 분리·만료 스윕·read-path 만료필터는 [`04`](./04-inventory.md)·[`SCHEMA.md`](./SCHEMA.md) §8-1(read-path 필터는 oversell 일부만 막음을 유의).
- **over-ship** → `quantity_shipped_so_far` 조건부 원자 UPDATE(0행=거부, §3-2), `*_refunded_so_far`와 동형.
- **결제·환불·송금 따닥** → 멱등키 UNIQUE(`order_payment`·`payment_cancel`·`payout`) + PG에도 `Idempotency-Key` 전달(end-to-end). [`03`](./03-idempotency-and-concurrency.md).
- **정산 이중 적재** → `settlement_line.source_event_id` UNIQUE(재INSERT 23505→정상 swallow + ack).
- **상태 역전이**(늦은 배송/웹훅) → `most_recent` 부분 UNIQUE + 가드 UPDATE. **이벤트 순서**는 파티션 키(§5).
- **마감 race(정산 미아)** → `settlement_cycle.status`에 `CLOSING` 삽입, 2-스텝 원자 마감(① `OPEN→CLOSING, cutoff_at=now()` ② SUM은 `eligible_at >= period_start AND eligible_at < cutoff_at` 반열림) + CLOSED/CLOSING 기간 INSERT·`cycle_id` UPDATE 거부 트리거 + **락 모드 비대칭**(유입=cycle 행 `FOR SHARE` / 마감=`FOR UPDATE`) + 마감 전 진단 게이트(미귀속·미충족 라인 0행 확인). 마감 후 도착분은 다음 OPEN 주기로 흡수. (메커니즘·starvation·데드락 ↓ 토글)
- **대사(reconciliation)** → ① PG 입금 4-way(§4-4), ② 정산 원장 SUM vs 정산서 캐시, ③ 재고 장부 vs 실물, ④ 택배 추적 vs 내 상태. 주기 대사로 break 탐지([`08`](./08-settlement-and-reconciliation.md), 워크스루 [`01-append-only`](../flab-mentoring/2026-06-20/01-append-only-vs-state-update.md) §4). **단 "drift≠0=알람"은 틀렸다 — PG 입금 시차로 정상 drift가 상존**하므로 recon 상태 모델로 정상/사고를 분리한다(↓ 토글).

<details>
<summary>🔒 <b>마감 vs 유입의 락 모드 비대칭 — 왜 `FOR SHARE`만으론 안 되고, starvation·데드락은?</b></summary>

**먼저 흔한 오류**: "cycle 행을 `FOR SHARE`로 직렬화한다"는 **부정확하다.** `FOR SHARE`는 *공유 락*이라 유입끼리·마감끼리 같은 모드면 **동시에 통과**해 상호배제가 안 된다. 핵심은 **모드 비대칭**이다(PostgreSQL 행 락 호환성):

| 보유 ↓ \ 요청 → | FOR SHARE | FOR UPDATE |
|---|---|---|
| **FOR SHARE** | 호환(동시 통과) | **충돌(대기)** |
| **FOR UPDATE** | **충돌(대기)** | 충돌(대기) |

- **유입**(라인 INSERT 전): cycle 행 `SELECT … FOR SHARE`. 유입끼리는 호환이라 안 막힘(정상 처리량 유지). = "이 주기에 매달리는 중, 끝내지 마".
- **마감**: cycle 행 `SELECT … FOR UPDATE` → `OPEN→CLOSING` 플립. = "내가 닫는 중, 새로 매달리지 마".
- SHARE↔UPDATE **비호환**이므로: 진행 중 유입이 있으면 마감은 그들이 커밋될 때까지 대기(→ 진행 라인 누락 0). 마감이 먼저 UPDATE를 잡으면 이후 유입은 깨어나 status가 `CLOSING`임을 **재확인**하고 다음 OPEN 주기로 향한다. **= readers-writer 락.**

**락만으로는 불완전 — 트리거가 불변식을 강제한다.** 락은 *타이밍 조율*일 뿐, "CLOSING이면 귀속 금지"라는 **불변식 자체**는 INSERT/`cycle_id` UPDATE 트리거가 status 재확인 후 `RAISE EXCEPTION`(fail-closed)로 강제한다. **락=경합 조율, 트리거=불변식 강제** — 역할이 다르다.

**starvation(마감 굶음)**: PG 행 락은 공정성을 보장 안 해, SHARE가 끊임없이 들어오면 UPDATE가 굶을 *이론적* 위험이 있다. 완화 — 마감은 저빈도, 유입의 SHARE 보유는 ms 단위(INSERT 1건+커밋)라 틈이 생긴다. 더 견고히는 **2단계 마감**: 짧은 TX로 `OPEN→CLOSING`만 플립(이후 신규 SHARE는 다음 주기로) → 별도 TX로 cutoff 이전만 집계. UPDATE 보유 구간을 최소화해 starvation 창을 닫는다. `lock_timeout`으로 N초 못 잡으면 실패·재시도·알람.

**데드락**: cycle(부모)↔line(자식) 락 순서를 **항상 cycle 먼저**로 통일하면 사이클이 안 생긴다. 여러 셀러를 한 배치로 닫을 땐 cycle 행을 **`ORDER BY cycle_id`로 정렬해 잠가** 전역 순서를 통일(데드락 예방 > 탐지 후 40P01 abort).

**advisory lock / SERIALIZABLE 대안**: `pg_advisory_xact_lock(seller_id)`는 행 없는 논리 자원에 맞지만 *애플리케이션 규약*이라 트리거 강제 불가 → 보완 관계. `SERIALIZABLE`은 낙관적(충돌 시 40001 abort+재시도)이라 *무거운 마감 배치가 abort되면 재실행 비용*이 커 비관적 명시 락이 예측 가능·디버깅 쉬움.

</details>

<details>
<summary>📊 <b>drift 운영 — 정상 drift vs 사고 drift 상태 모델 + 대사 스냅샷 일관성 + 격리 결정 트리</b></summary>

**전제 교정**: "drift=0 / 아니면 break"는 **닫힌 cutoff 구간에서만 참**이다. PG 입금은 결제~정산파일 입금 사이 시차(D+N)가 있어, 진행 중 구간엔 *원장엔 SALE 있으나 PG 파일 미도착* = **정상 drift가 상존**한다. 모든 drift를 알람하면 false-positive 폭증(alert fatigue)으로 알람이 무력화된다.

**recon 상태 모델** — 매칭 단위에 상태를 부여하고 **drift를 상태별로 분해**:

| 상태 | 의미 | 판정 |
|---|---|---|
| **EXPECTED** | SALE 확정, PG 입금 예정일 미도래 | 정상 |
| **IN_TRANSIT** | 예정일 도래 ~ grace 이내, 미매칭 | 정상(대기) |
| **SETTLED** | PG 입금파일과 건별 금액 매칭 완료 | 정상(종결) |
| **BREAK** | grace 초과 미입금 OR 금액 불일치 OR 원장에 없는 입금 | **사고** |

- 알람은 **`Σ(BREAK) ≠ 0`일 때만**. 합격 조건은 "drift=0"이 아니라 **"BREAK=0 AND 도래분 전부 SETTLED"**.
- 따라서 §4-4의 "`CLEARING_PG` 비0 = 알람"은 정밀하게는 **"입금 예정일 *지난* `CLEARING_PG` 잔액 = 알람"**(미도래분은 정상 미청산).
- **임계값**: EXPECTED→IN_TRANSIT은 PG 정산 스케줄(D+N) 도래, IN_TRANSIT→BREAK는 예정일+**grace**(PG사 지연 분포 p99+안전마진) 초과. 1원 미만 라운딩은 tolerance로 흡수(break 아님).

**스냅샷 일관성(read skew 방지)**: 대사가 원장 SUM과 PG 파일을 *다른 시점*에 읽으면 그 사이 커밋으로 가짜 drift가 뜬다. 방어 — ① **닫힌 cutoff 구간만 대사**(움직이는 표적 배제), ② 대사 트랜잭션을 **REPEATABLE READ**로 열어 *트랜잭션 시작 시점 MVCC 스냅샷 고정* → 두 쿼리가 동일 스냅샷을 봄. 대사는 읽기 전용이라 RR로 충분(SERIALIZABLE이 막는 write skew는 불필요한 오버헤드).

**false-positive를 break로 오인 안 하기**: grace period 자체가 "타이밍성 미입금이 따라잡을 시간"을 줌(grace 내면 절대 break 아님). 추가로 **hysteresis** — 연속 M회 대사에서도 미매칭+grace 초과일 때만 BREAK 확정. break도 자동 hold가 아니라 알람→사람 확인→hold(또는 고액만 자동 hold).

**격리 vs 전체 정지 결정 트리**:
```
break 탐지
 ├─ 단일 셀러·소수 건? → 그 셀러만 settlement_hold(scope=SELLER/LINE) + 나머지 정상 진행 (격리=기본)
 ├─ 다수 셀러·구조적?(PG 파일 전체 누락·특정 결제수단 전건·체계적 차액) → 공통 원인 의심,
 │                                     전체 payout 일시 정지 + 근본원인 조사 (셀러별 hold 무의미)
 └─ tolerance 이내(라운딩)? → break 아님, 자동 흡수
```
한 셀러 break로 전체를 멈추면 과잉 대응, 구조적 누락을 셀러별 hold로 처리하면 수백 hold 폭증 — **격리가 기본, 전체 정지는 "공통 원인" 신호일 때만.**

</details>

---

## 9. 스케일 — 읽기 모델·파티셔닝·정산 배치 엔지니어링

- **CQRS read model** — 셀러 정산 대시보드·주문 목록은 매번 집계하면 느리므로 `settlement`(집계 캐시)·`order_summary`로 1행 조회로 환원([`10`](./10-db-scaling-capacity.md) 레버 3).
- **파티셔닝/아카이빙** — `pg_message_log`·`shipment_event`·`settlement_line` 등 append-only 로그는 `created_at` RANGE 파티셔닝 + DETACH→콜드 분리([`10`](./10-db-scaling-capacity.md)).
- **샤딩 후보 키** — 주문계 `customer_id`, 정산계 `seller_id`.
- **정산 배치는 대용량 배치 엔지니어링이 본체** — 라인 적재는 실시간이어도 주기 집계·정산서·payout 산출은 대용량 배치입니다(배민 정산 코드 약 절반이 Spring Batch). 세 원칙:
  1. **집계는 WAS 루프가 아니라 DB-side `GROUP BY`/`SUM`** — 배민 측정 WAS 12s vs DB 8s/2.5만건, 유지보수성으로 DB-side 선택("net=SUM(line)을 앱이 아니라 쿼리로 도출" 원칙과 동형, [우아한형제들](https://techblog.woowahan.com/2711/)).
  2. **ItemReader는 LIMIT/OFFSET 금지·no-offset 키셋**(`WHERE line_id < :lastId ORDER BY line_id`) — 배민 869,000건 21분→4분36초([우아한형제들](https://techblog.woowahan.com/2662/)).
  3. **집계 윈도우는 반열림** `period_start ≤ eligible_at < period_end`(배민이 닫힌 between→반열림으로 리팩토링, 23:59:59 경계 유입분 누락 방지).
- **OLAP 한계** — `settlement` read model은 셀러 대시보드엔 충분하나 수십억 행 다차원 정산 분석은 RDB 한계라 OLAP(StarRocks/Druid) 분리(CDC 스트리밍).

---

## 10. 트레이드오프 & 면접 방어 포인트

- **"정산을 결제 직후가 아니라 구매확정 후에?"** → 환불 리스크 + 법정 에스크로. 결제 직후 정산하면 환불 시 이미 준 돈을 회수(마이너스 정산·청구). 구매확정으로 미루면 환불 창의 *대부분*이 닫혀 안정 — **단 구매확정=정산 100% 확정 아님**(자동확정 후에도 반품·교환 기간 잔존 → 쿠팡 주정산 70% 선지급/30% 익익월 유보. 비율 유보는 쿠팡 특유, 11번가·스마트스토어·토스 지급대행은 100%, 코어는 후자 기본). 법적으로도 선지급식은 전자상거래법 §24 구매안전서비스 의무 대상(카드 건은 카드사 보호 갈음 제외).
- **"자동확정일은?"** → 상수가 아니라 정책값(쿠팡 7 / 네이버·11번가 8 / 추적불가 발송일 28 / 해외 45, 모두 달력일) + **추적불가 fallback 타이머**로 정산 미아 방지(§3-1).
- **"멀티셀러 결제 1건인데 정산은 셀러별?"** → 단일 결제로 받고(토스 지급대행형 B), `settlement_line.seller_id`로 사후 분해. 부분취소 격리는 `source_id·seller_id`별 개별 행이라 구조적 보장. 분할 합·부담주체는 `trg_discount_funding_split` fail-closed.
- **"배송 상태를 단일 컬럼 UPDATE하면?"** → 택배 추적은 순서 뒤바뀜·중복 → append-only `shipment_event` + most_recent + 가드 + `carrier_dedup_key`.
- **"정산 금액이 결제와 1원 어긋나면?"** → 잔액은 원장 SUM으로 도출(`ck_settline_sign`이 부호를 INSERT 시 강제, 선형 SUM은 부호 상쇄로 새므로) + 4-way 대사로 break 탐지(§4-4), 보정은 역분개 append.
- **"payout 송금 타임아웃은?"** → `IN_PROGRESS`(UNKNOWN 버킷), 재조회 전 재송금 금지. `PAID`는 토스에 없는 값.
- **솔직한 한계** → 단일 PG·단일 통화 가정(다통화는 통화별 원장·환율 시점, [`01-money`](./01-money-currency-tax.md)). 셀러 마스터·KYC·롤링 리저브는 의도적 스코프 밖(§0). 수십억 행 정산 분석은 RDB read model 한계 → OLAP 분리.

---

## 11. 참고할 DB 구조·기술 자료 (1차 출처)

**레포 내부 (정본)**
- [`SCHEMA.md`](./SCHEMA.md) — 38테이블 풀 도메인(`reservations`·`settlement_periods`(carryover)·`settlement_lines`·`commission_rates`·`pg_settlement_raw`·`recon_matches`·복식부기 원장 포함). 이 문서의 모태
- [`SCHEMA-toss.md`](./SCHEMA-toss.md)(주문·결제 코어, `use_escrow`) · [`08`](./08-settlement-and-reconciliation.md)(정산·대사) · [`07`](./07-ledger-double-entry.md)(원장) · [`04`](./04-inventory.md)(재고 예약) · [`09`](./09-outbox-saga-and-audit.md)

**기술블로그·공식문서 (이 개정의 1차 출처)**
- **우아한형제들** — 정산 시스템·Spring Batch·키셋 페이징: [정산 시스템](https://techblog.woowahan.com/2711/) · [대용량 배치 no-offset](https://techblog.woowahan.com/2662/) · [선물하기 재고 동기 차감](https://techblog.woowahan.com/2709/)
- **토스(SLASH)/토스페이먼츠** — [결제 원장 UPDATE→INSERT-only 마이그레이션](https://toss.tech/article/payments-legacy-5) · [지급대행 payout(상태·KYC·pending/available)](https://docs.tosspayments.com/guides/v2/payouts) · [미수금/정산 용어](https://docs.tosspayments.com/resources/glossary/settlement)
- **전자상거래법** — [구매안전(에스크로) 의무, easylaw](https://easylaw.go.kr/CSP/CnpClsMain.laf?popMenu=ov&csmSeq=25&ccfNo=3&cciNo=3&cnpClsNo=2)
- **쿠팡 정산** — 주정산 70/30 유보·익익월 지급(windly 정리), 자동 구매확정 D+7
- **다중정산/지급대행** — [KICC 다중정산(결제시점 split형 A)](https://docs.kicc.co.kr/docs/support/glossary/multi-settlement/), [에스크로 vs 지급대행(PortOne)](https://blog.portone.io/ps_escrow-vs/)

**오픈소스 커머스 스키마**
- **Medusa.js**(Node, 모듈형 — order/fulfillment/payment 분리가 교과서적) · **Saleor**(Python, 결제 잔액도 append-only TransactionEvent SUM·`pending` 1급) · **Spree** · **Broadleaf/Bagisto**(Java/Spring)

**정산·원장 심화** — Modern Treasury / Square / TigerBeetle / Uber LedgerStore([`01-append-only`](../flab-mentoring/2026-06-20/01-append-only-vs-state-update.md) §1 ⑤)

---

## 12. 한 장 요약 (백지 복원용)

```
페르소나: 셀러 입점 오픈마켓(쿠팡/컬리류) — 토스 PG, 택배 배송, 셀러 주기 정산. 정산은 사후 지급대행형(B).

4 Bounded Context (이벤트로 연결, 직접 조인 금지):
  주문(무엇을 샀나) → 결제(돈 들어옴, 토스) → 배송(어디로 갔나) → 정산(누구에게 얼마)
  정산은 두 다리: ① PG→플랫폼 입금 대사(4-way) ② 플랫폼→셀러 송금(payout 지급대행)

핵심 시점 2개:
  ① 재고 = (A)결제시점 동기차감 / (B)예약→확정 두 패턴. 본 문서는 (B)=reservations.
     oversell 차단 핵심은 "조건부 원자 차감(WHERE qty>=:q)", 예약은 선점 여부.
  ② 정산 대상 = "구매확정"(자동확정=정책값: 쿠팡7/네이버·11번가8/추적불가 발송일28/해외45, 달력일).
     추적불가 fallback 타이머 없으면 정산 미아. 구매확정≠정산 100%확정(쿠팡 70/30 유보).
     법적으로 선지급식은 전자상거래법 §24 에스크로 의무(카드 제외).

신규 도메인:
  배송  shipment(셀러·운송장·trackable·auto_confirm_due) + shipment_item(over-ship=quantity_shipped_so_far 게이트)
       + shipment_event(append-only, carrier register-then-callback, dedup=carrier_code+tracking_no) + return_request/return_item(검수 분해) + 교환 전용 흐름
  정산  settlement_cycle(반열림·EXCLUDE 겹침금지·셀러별·carryover≤0) → settlement_line(부호 amount+ck_settline_sign
       +source_event_id UNIQUE+commission_base) → settlement(net 항등식 CHECK, 음수가능) → payout(REQUESTED→IN_PROGRESS→COMPLETED/FAILED, payout_event) → PG입금 대사(pg_settlement_raw·CLEARING_PG=0)

정합성: 멱등키 UNIQUE+PG 멱등키 전달, 부호는 INSERT CHECK(SUM이 아님), 상태=append-only+most_recent+가드,
        이벤트 멱등(source_event_id)+순서(파티션키)+역전이(가드) 3중 분리, 정산 잔액=원장 SUM, 4-way 대사
동기/비동기: 돈·재고·hold/release=동기, 배송 생성·정산 적재=이벤트(소비자 멱등). 마감=Spring Batch(DB집계·no-offset·반열림)

한 줄: "주문은 배송·정산을 모른다. 사실(PAID·DELIVERED·CONFIRMED·REFUNDED)만 이벤트로 흘리고,
        정산은 그 사실로 부호 있는 append-only 원장을 쌓아 SUM으로 지급액을 도출하되,
        PG가 실제 입금했는지(4-way 대사)·자동확정 정책·미수금 이월·송금 UNKNOWN까지 1급으로 다룬다."
```

---

## 13. AI 시대 구현 방법론 — DDD·TDD·BDD·Mock 경계와 "테스트가 못 보는 것"

> **무엇**: 이 설계서(§0~§12)를 **AI(LLM)로 코드화할 때** 어떤 방법론을 어떤 층위에서 채택해야, AI의 구조적 한계까지 끌어올려 production 수준으로 구현되는가. 결론부터 — **DDD·TDD·BDD는 고르는 게 아니라 *층위가 다른 축*이고, 진짜 승부는 "테스트가 구조적으로 못 보는 영역(동시성·순서·돈 드리프트)을 무엇으로 덮느냐"**입니다.

### 13-0. 결론 한 문장 + "경쟁이 아니라 다른 축"

> **DDD로 *경계와 불변식의 위치*를 코드·타입·DB 제약으로 박제(AI가 못 어기게)하고, 그 경계 안에서 TDD로 불변식을 *검증 가능한 명세*로 고정(AI에게 채점 기준으로 제공)하며, 복잡한 비즈 규칙(환불 조합·멀티셀러)은 BDD 스타일로 표현합니다. Mock은 "진짜 외부 경계(PG·택배·시계)"에만 치고 DB·도메인 로직은 절대 mock하지 않습니다(Testcontainers 실 DB). 테스트가 못 보는 영역은 property 테스트·결정론적 시뮬레이션과 *런타임 대사·DB 불변식*으로 덮습니다 — AI 코드엔 "모르는 버그"가 더 많으므로 이 런타임 안전망의 가치가 AI 시대에 오히려 커집니다.**

```
        구조(WHERE — 코드를 어디에 둘까)        프로세스(HOW — 어떻게 도달·보증할까)
   ┌──────────────────────────────┐      ┌─────────────────────────────────────┐
   │  DDD                          │      │  TDD                  BDD            │
   │  · bounded context (4개)       │      │  Red→Green→Refactor   Given/When/Then│
   │  · aggregate (불변식 경계)      │      │  단위 불변식 구동       행위·시나리오 구동 │
   │  · 불변식의 소유자              │      │                                     │
   │  · 도메인 이벤트 (계약)         │      │  "REFUND는 음수다"     "단순변심+카드면 │
   │  · 식별자 참조(Long orderId)    │      │   를 테스트로 박음       수수료 환원"     │
   └──────────────────────────────┘      └─────────────────────────────────────┘
            │  지도(map)                            │  지도 위를 걷는 방법
            └──────────────── 같이 쓴다 ─────────────┘
   DDD가 "정산은 settlement_line을 소유한다"고 경계를 정하면 →
   TDD가 "REFUND가 들어오면 amount는 음수다"를 테스트로 고정하고 →
   BDD가 "단순변심+카드 환불 시 판매수수료가 환원된다"를 시나리오로 박는다.
```

**축이 다르다는 게 핵심입니다.** DDD는 *지도*(어느 컨텍스트에 무엇을 두고 누가 진실을 소유하나), TDD/BDD는 *그 위를 걷는 방법*(어떻게 짜고 무엇이 그것을 지키나). "DDD냐 TDD냐"는 "지도냐 걷기냐"처럼 잘못된 질문입니다.

### 13-1. DDD — "AI가 넘으면 안 되는 선"을 코드·타입·DB로 박제

이 문서는 이미 전술적 DDD 그 자체입니다. §1·§3의 결정이 전부 DDD 어휘로 번역됩니다.

| 이 문서의 결정 | DDD 용어 | AI 코딩에서 왜 중요한가 |
|---|---|---|
| 주문·결제·배송·정산 4개 분리(§1) | **Bounded Context** | "한 번에 한 컨텍스트만" 짜게 하는 AI 작업 단위 = 컨텍스트 윈도우 경계 |
| `settlement_line`이 정산 진실 소유(§4) | **Aggregate Root** | "이 불변식을 누가 지키나"가 명확 → AI가 로직을 엉뚱한 곳에 못 둠 |
| `@ManyToOne Order` ❌ → `Long orderId` ✅(§3-1) | **식별자 참조 / aggregate 경계** | AI가 객체 그래프 타고 cross-context 결합 만드는 걸 *구조적으로 차단* |
| `OrderPaid`·`PurchaseConfirmed`(§5) | **Domain Event** | 컨텍스트 간 통신 계약 = AI가 짤 때의 인터페이스 명세 |
| `ck_settline_sign`·`commission_base`(§4) | **Invariant(불변식)** | 테스트로 박을 "진실"의 목록 그 자체 |

<details>
<summary>🧱 <b>DDD 전체 체계 — 전략·전술·왜·실무·장단점 (위 표 5줄의 뿌리)</b></summary>

위 표는 DDD라는 체계의 *결과물*일 뿐이다. 뿌리부터 정리한다.

**DDD란 — 코드 구조를 기술(DB·프레임워크)이 아니라 *비즈니스 도메인 구조*에 맞추는 설계 철학**(Eric Evans, 2003). 문제의식: 복잡한 도메인에서 코드가 커지면 "이 규칙이 어디서 지켜지는지 아무도 모르는" 상태(빈혈 모델 — 거대한 Service + 빈약한 Entity, 규칙이 사방에 흩어짐)가 된다. DDD는 **규칙(불변식)을 그 규칙이 속한 도메인 객체 안에 가둬** 이를 막는다. 두 층으로 나뉜다 — **전략적**(경계를 어디에)과 **전술적**(경계 안을 어떤 패턴으로).

**전략적 DDD — 큰 그림**
- **유비쿼터스 언어(Ubiquitous Language)** — DDD의 출발점. 개발자·기획·도메인 전문가가 *같은 단어*를 쓰고 그 단어가 코드에도 그대로. 이 문서의 `settlement_line`·`auto_confirm_due`·`carryover`·"정산 미아"·"역분개"가 그 실천. (복잡한 도메인 버그 절반은 "같은 단어 다른 이해"에서 온다 → 언어 통일로 봉쇄.)
- **도메인 → 서브도메인 등급** — `Core`(경쟁력 원천, 가장 공들임 = 정산) / `Supporting`(지원 = 배송) / `Generic`(어디나 똑같음, 사서 씀 = 결제→토스 위임). 자원 배분의 근거. §0의 "KYC·롤링 리저브 스코프 밖"이 이 판단.
- **Bounded Context(경계 컨텍스트)** — *핵심 중 핵심*. 하나의 모델·용어가 일관되게 통하는 경계. "Order"가 주문/배송/정산에서 다른 뜻이라 하나의 거대 클래스로 통합 금지. §1의 4개 컨텍스트가 이것 — 각자 진실 소유 + 남의 내부 안 건드림 + 이벤트로만 통신.
- **Context Map(관계)** — `ACL`(부패 방지 계층: 외부 PG·택배 모델이 내 도메인 오염 못 하게 번역, 예 `carrier_code_mapping`·`payout.status` 토스값 정렬) / `Published Language`(컨텍스트 간 계약 = 도메인 이벤트 페이로드).

**전술적 DDD — 경계 안 빌딩 블록**
- **Entity**(ID로 추적, 상태 변해도 같은 것 — `shipment`·`settlement`) vs **Value Object**(ID 없이 값으로 정의·불변 — `Money`·`ship_address` 스냅샷). VO 불변이 통화 안전·스냅샷 동결로 버그를 타입으로 봉쇄.
- **Aggregate + Aggregate Root** — *심장*. 함께 변경·함께 일관성 지킬 객체 묶음 + 그 대표(루트). 외부는 루트로만 접근(예: `return_request`가 루트, `return_item`은 루트 통해서만). **3대 규칙**: ① 불변식 경계 = 트랜잭션 경계(한 aggregate는 한 TX에 통째 일관) ② aggregate 간 참조는 객체가 아니라 **ID로**(`Long orderId`, §3-1) ③ **한 TX는 하나의 aggregate만** 수정(나머진 이벤트 → §5의 동기/비동기 분기 근거).
- **Repository**(aggregate 보관함, SQL 감춤) / **Domain Service**(여러 객체 걸친 규칙 — 환불 조합 §6, 빈혈 Service와 구분) / **Domain Event**(과거형 사실 — `OrderPaid`) / **Factory**(생성 시 불변식 강제 — 부호 맞는 라인만 생성).

**왜 이렇게 정의하나 — 모든 패턴이 한 목표로 수렴: "불변식을 안전하게 지키며 복잡도를 다스린다."**

| 결정 | 이유 |
|---|---|
| 경계를 긋는다 | 변경 파급 차단·불변식 소유권 명확·인지부하 분할(§1 토글) |
| aggregate로 묶는다 | 불변식을 한 TX·한 책임자가 지키게 |
| 간 ID 참조 | cross-context 결합·우발적 수정 차단 |
| 한 TX=한 aggregate | 락 경합·결합 최소화, 나머진 최종 일관성 |
| 이벤트 통신 | 시간 결합 분리·장애 격리·확장 자유 |

**실무 흐름**: 이벤트 스토밍(전문가와 사건을 깔아 경계·aggregate 발견) → 언어 통일 → 경계 긋고 **modular monolith로 시작**(§1) → aggregate·불변식을 타입·DB제약으로 박제 → 필요해지면 물리 분리(이때 outbox). **수위**: 전략(경계·언어)은 거의 항상, 전술(aggregate·VO)은 **core 도메인에만** 깊게(이 문서가 정산엔 깊게, 결제는 위임).

**장단점(정직하게)**

| 장점 | 단점·비용 |
|---|---|
| 규칙이 한곳에 응집 / 변경 파급 차단 | 학습 곡선 가파름(용어 많음) |
| 불변식 소유 명확 → 정합 사고 감소 | **단순 CRUD엔 과함**(오버엔지니어링) |
| 비즈니스-코드 번역 손실 제거 | 도메인 전문가 협업 필수 |
| AI에게 줄 "진실의 지도" 제공 | aggregate 경계 오판 시 오히려 독 |
| MSA로 자연스러운 진화 | 잘못 적용 시 "이름만 DDD" |

> **쓰면 안 되는 곳**: 비즈 규칙 거의 없는 게시판·관리자 CRUD. DDD는 **불변식이 빽빽한 복잡 core 도메인**(이 문서의 정산·환불·재고)을 위한 도구다.

> **AI 코딩 관점 한 줄**: DDD가 "경계·aggregate·불변식·이벤트"라는 *진실의 지도*를 먼저 그려주므로, AI에게 "이 경계 안에서·이 불변식 지키며·이 이벤트 계약대로 짜라"는 *검증 가능한 제약*을 줄 수 있다. 지도가 없으면 AI는 그럴듯하나 경계를 넘나드는 코드를 낸다(아래 역분개 사례).

</details>

#### 불변식(invariant)이란

**= 트랜잭션 전후에 *언제나* 참이어야 하는 규칙.** 깨지면 데이터가 "말이 안 되는 상태"가 됩니다("삼각형 세 각의 합 = 180°"처럼 절대 참인 명제). DDD의 **Aggregate = 불변식을 함께 지켜야 하는 데이터 묶음 + 그 일관성을 책임지는 경계**입니다.

```
Aggregate = 불변식의 경계
   ┌─────────────── 정산 Aggregate ───────────────┐
   │  settlement_line 들                            │
   │  불변식: 부호 정합(ck_settline_sign), net=SUM,  │
   │         멱등(source_event_id), 한 번만 적재      │
   │  → 이 묶음 밖에선 누구도 settlement_line을       │
   │     직접 INSERT/UPDATE 못 한다 (경계가 보호)     │
   └───────────────────────────────────────────────┘
```

이 시스템의 불변식 목록(=§8) → AI에게 "절대 어기지 마"라고 줄 규칙이자 §13-2에서 테스트로 박을 항목:

| 불변식 | 깨지면 |
|---|---|
| 출고 누적 ≤ 주문 수량(`quantity_shipped_so_far ≤ quantity`) | over-ship(산 것보다 많이 배송) |
| REFUND 행 amount는 **항상 음수** | 환불인데 셀러 돈이 늘어남 |
| 한 시점에 셀러·카테고리 수수료율은 **하나** | 정산 시 어떤 율 쓸지 모호 → 분쟁 |
| 수수료 = `commission_base`에 곱함(`gross` 아님) | 셀러부담 할인까지 수수료 매김 → 과대청구 → 소송 |
| `net = gross − commission − refund` | 정산서 숫자 불일치 |
| 같은 이벤트는 정산에 한 번만(`source_event_id` UNIQUE) | 중복 지급 |

#### 왜 AI 코딩에서 DDD가 *특히* 더 중요해졌나 — append-only와 "역분개"

AI의 근본 한계는 **"국소적으로 그럴듯하나 전역 불변식을 모른다"**입니다. "환불 함수 짜줘"를 주면 AI는 인간 직관대로 이렇게 짭니다:

```sql
-- ❌ AI가 자연스럽게 내놓는 코드 — 동작하고, 합계도 맞고, 단위 테스트도 통과
UPDATE settlement_line SET amount_minor = 0 WHERE line_id = 100;  -- SALE을 0으로 덮음
```

이게 왜 사고인가 — **append-only 원장의 "역분개(reversal)"** 원칙을 위반하기 때문입니다.

```
❌ UPDATE 모델 (덮어쓰기)            ✅ 역분개 모델 (반대부호 신규 행 INSERT)
─────────────────────              ──────────────────────────────────────
line 100 | SALE | +10000            line 100 | SALE              | +10000  ← 그대로 둠
        ↓ UPDATE로 0 덮음           line 101 | COMMISSION        |  -1000  ← 그대로 둠
line 100 | SALE | 0                 line 205 | REFUND            | -10000  ← 새로 추가
                                    line 206 | REFUND_COMMISSION |  +1000  ← 새로 추가
"10000 팔렸었다"는 사실 증발                                       ─────────
왜 0인지 알 수 없음                                               net = SUM = 0  (자동 상쇄)
감사·추적·복구 불가                  모든 사건의 역사 보존, 감사·재현·디버깅 가능
```

- **"역(逆)분개"** = 원본과 *반대 방향(부호)*의 분개를 새로 더해 상쇄. SALE이 +10,000이면 REFUND를 −10,000으로 **추가**해 합이 0이 되게 합니다.
- **"신규 행"** = UPDATE가 아니라 INSERT. 줄을 고치지 않고 새 줄을 긋습니다(회계 500년 원칙 — 잉크로 쓰고 지우개 금지, 틀리면 "취소한다"는 새 줄).
- **정정조차 UPDATE 안 함** — 틀린 값도 그 행을 수정하지 않고, 역방향 행 + 올바른 행을 추가합니다(§4-2b `{source_event_id}:correction:N`).

> **AI가 왜 이걸 어기나:** "왜 append-only인가"는 이 도메인의 역사·맥락에 있고 코드 어디에도 안 적혀 있습니다. 그래서 **프롬프트로 "조심해"가 아니라 DB로 막습니다** — `settlement_line`에 UPDATE 권한을 안 주거나(INSERT-only), 트리거로 UPDATE를 거부(§13-5의 DB 불변식). **DDD의 실전 효용 = AI가 못 어기게 불변식을 컴파일러·타입·DB 제약으로 강제하는 것.** 프롬프트보다 100배 강력합니다.

<details>
<summary>🛡️ <b>"AI가 짠 코드를 리뷰로 잡으면 되지, 왜 구조로 막나?" (면접 방어)</b></summary>

**리뷰는 "그럴듯함"을 못 거른다 — 그게 AI가 가장 잘하는 거짓말이라서.** `UPDATE settlement_line SET amount=0`은 읽으면 멀쩡합니다. 합계도 맞고 테스트도 그린입니다. 리뷰어가 "이건 append-only 위반"이라고 잡으려면 *도메인 불변식을 머릿속에 들고 있어야* 하는데, 그건 사람마다·날마다 다릅니다.

- **코드 검증은 "그 코드를 지나갈 때만" 작동** — AI가 새 우회 경로(관리자 콘솔·배치·마이그레이션)를 짜면 그 경로엔 체크가 없습니다.
- **DB 제약은 데이터가 들어오는 *모든* 문을 지킴** — 누가 어떤 코드로 INSERT하든 `ck_settline_sign`이 양수 REFUND를 거부(23514)합니다. 깜빡할 수가 없습니다.
- **타입은 컴파일 시점에 막음** — `Long orderId`(객체 참조 불가)면 AI가 `getOrder().getStatus()`로 cross-context를 읽는 코드를 *애초에 작성 못* 합니다(import조차 안 됨, §3-1).

> 한 줄: **AI 코드의 안전망은 "사람의 리뷰"가 아니라 "AI가 물리적으로 못 어기는 구조"에 둔다 — 타입(컴파일), DB 제약(INSERT), 대사(런타임)의 3중.**

</details>

### 13-2. TDD — "검증 가능한 명세"로 AI에게 채점 기준을 준다

#### "검증 가능한 명세(executable specification)"의 의미

```
모호한 명세(자연어)              검증 가능한 명세(테스트 코드)
────────────────                ─────────────────────────────
"환불 잘 처리해줘"         →    "REFUND 라인은 음수다 + net은 0이다 +
                                원본 SALE 행은 그대로 남는다"
사람마다 다르게 해석            통과/실패가 기계적으로 판정
AI가 그럴듯한 헛소리 가능       AI가 헛소리하면 빨간불
```

**"검증 가능" = 결과가 참/거짓으로 명확히 판정됨.** "잘 처리"는 주관적(검증 불가), "net == 0"은 `assertEquals`(검증 가능). **테스트 = 모호함이 0인 명세**이고, AI에게는 자연어 프롬프트보다 압도적으로 정확한 지시입니다.

#### AI 코딩에서 TDD가 *부활한* 이유 — "닻(anchor)"

AI 코딩의 또 다른 한계는 **드리프트**입니다. AI에게 코드를 고치게 하면 고치는 과정에서 *원래 지키던 다른 불변식을 슬며시 깹니다*(사람보다 광범위하게 손대서 더 잘 깸). 테스트는 AI를 묶는 닻입니다:

```
1. 불변식 목록(§8)을 준다 — "이게 진실이다"
2. 불변식 → 테스트 이름 목록으로 변환 ("test_환불행은_음수다" — 이름이 곧 명세)
3. AI가 테스트 본문 작성 → 사람이 단언(assert)을 검수  ← 여기가 load-bearing
4. AI에게 "이 테스트를 통과시켜라" (모호한 "좋은 코드" 금지)
5. 그린 확인 → 리팩토링 (AI가 망치면 즉시 빨개짐)
```

> **3번 주의:** AI가 테스트와 구현을 *동시에* 짜면 "구현에 맞춘 테스트"(구현이 틀려도 통과)를 낼 수 있습니다. 테스트가 명세이므로 **단언은 사람이 검수**하거나 테스트를 먼저 확정한 뒤 구현을 별도로 시킵니다.

#### 불변식 → 실제 테스트케이스 (AI에게 그대로 시킬 수 있는 것)

**(가) 환불 역분개 불변식**
```java
@Test
void 환불시_원본_SALE행은_변경되지_않는다() {            // append-only 증명
    var saleId = settlement.recordSale(orderItem, 10_000);
    var before = repo.findById(saleId);
    settlement.recordRefund(paymentCancel, 10_000);
    assertThat(repo.findById(saleId)).isEqualTo(before);  // 글자 하나 안 바뀜
    assertThat(settlement.netOf(sellerA)).isZero();        // net은 SUM으로 0
}
@Test
void REFUND를_양수로_INSERT하면_DB가_거부한다() {        // ck_settline_sign
    assertThatThrownBy(() -> repo.insert(line(REFUND, +100)))
        .hasMessageContaining("ck_settline_sign");        // 23514
}
```

**(나) over-ship 불변식(§3-2)**
```java
@Test
void 주문수량_초과_출고는_거부되고_카운터는_안_오른다() {
    var line = orderItem(quantity = 5);
    shipment.ship(line, 3);                               // 누적 3 OK
    assertThatThrownBy(() -> shipment.ship(line, 3))      // 누적 6 > 5 거부
        .isInstanceOf(OverShipException.class);
    assertThat(line.shippedSoFar()).isEqualTo(3);          // 실패가 카운터를 안 올림
}
```

**(다) 환불 조합표(§6) — 파라미터화로 6케이스 한 번에**
```java
@ParameterizedTest
@CsvSource({
  "BUYER_CHANGE,  CARD,      true,  false",   // 단순변심+카드: 수수료 환원, PG수수료 환원
  "BUYER_CHANGE,  TRANSFER,  true,  true",    // 현금성: PG_FEE_NONREFUND 차감
  "SELLER_FAULT,  CARD,      false, false",   // 셀러귀책: 판매수수료 미환원
})
void 환불수수료_조합(String reason, String method, boolean refundComm, boolean pgFee) {
    var lines = settlement.recordRefund(reason, method, 10_000);
    assertThat(lines.has(REFUND_COMMISSION)).isEqualTo(refundComm);
    assertThat(lines.has(PG_FEE_NONREFUND)).isEqualTo(pgFee);
}
```

**(라) 멱등(at-least-once)**
```java
@Test
void 같은_이벤트를_두번_받아도_한번만_적재된다() {
    var e = orderPaidEvent(sourceEventId = "evt-123");
    settlement.consume(e); settlement.consume(e);          // 중복 수신
    assertThat(repo.countBySourceEventId("evt-123")).isEqualTo(1);
}
```

#### TDD를 "어디에" 쓸지 (현실적 분배)

순수 TDD를 모든 코드에 강요하면 비효율입니다.

| 영역 | 전략 | 이유 |
|---|---|---|
| 돈·정산·부호·멱등(§4·§8) | **엄격 TDD**, 테스트 먼저 | 틀리면 돈 사고, 불변식이 테스트로 직역됨 |
| 상태머신·역전이(§3-3·§6 3분기) | **TDD**, 전이표를 매트릭스로 | 불법 전이=0행, 망 형태라 적합 |
| CRUD·DTO·조회 API | 테스트 나중/가벼운 통합 | 불변식 없음, TDD 오버헤드만 큼 |
| CQRS read model·집계(§9) | 통합 테스트(실 DB) | 로직보다 쿼리 정확성이 본질 |

### 13-3. Mock 경계 — DB·도메인은 절대, 외부만 (AI 한계를 가르는 결정)

**Mock = 진짜 객체 대신 끼우는 가짜 대역**("이 메서드 부르면 이걸 반환한 셈 쳐"). 선을 잘못 그으면 **"통과하는데 production에서 터지는"** 최악의 테스트가 됩니다.

```
✅ Mock 해도 됨 (내가 통제 못 함)        ❌ 절대 Mock 금지 (검증 대상 그 자체)
─────────────────────────              ──────────────────────────────────
토스 PG(confirm/cancel/payout)          DB ← 불변식 절반이 여기 있음
  — 외부 소유, 돈 나감                      (ck_settline_sign, EXCLUDE 겹침금지,
택배 추적공급사(register/callback)          부분 UNIQUE most_recent, 조건부 원자
시계 now()/스케줄러 — 비결정적             UPDATE, DEFERRABLE 트리거)
  (mock 아니라 주입 가능한 Clock)        도메인 로직·정산 계산·aggregate 불변식
메시지 브로커(아웃박스→소비)               ← "테스트하려는 대상"
```

**경계 원칙: "내가 소유하지 않고·느리고·비결정적이고·돈이 나가는 것"만 mock.**

#### 왜 도메인 로직을 mock하면 안 되나 — 동어반복

```java
// ❌ 정산 계산기(=검증 대상)를 mock함
SettlementCalculator calc = mock(SettlementCalculator.class);
when(calc.netOf(sellerA)).thenReturn(9_000);     // "9000 반환한 척 해"
assertThat(calc.netOf(sellerA)).isEqualTo(9_000); // ← 아무것도 검증 안 함(동어반복)
//   실제 계산(SALE − COMMISSION)이 틀려도 통과. 진짜 로직을 mock으로 건너뜀.
```

> **핵심: mock한 것 = 검증되지 않은 것.** 테스트하려는 대상(System Under Test)은 절대 mock하지 않습니다. DB를 mock하면 가짜 repo가 "양수 REFUND"를 그냥 받아주지만 진짜 DB는 23514로 거부 → **Testcontainers로 진짜 PostgreSQL을 띄워야** DB 불변식까지 검증됩니다.

#### AI의 mock 남발 성향 — 명시적으로 억제

AI에게 "환불 서비스 테스트 짜줘"라고 하면 **`paymentGateway.cancel()`도 mock, `repository.save()`도 mock**해서 `verify(repo).save(any())` 류 — **"호출됐는지(interaction)"만 보는** 테스트를 냅니다. 이건 구현을 복사한 동어반복이라, AI가 리팩토링하면 깨지거나(취약) 로직이 틀려도 통과(무의미)합니다. AI에게 줄 규칙:

1. **상태(state)를 검증하라, 상호작용(verify)을 검증하지 마라** — "save 호출됐나"가 아니라 "DB에서 다시 읽었을 때 REFUND가 음수인가".
2. **DB·도메인은 mock 금지, Testcontainers를 써라.**
3. **mock은 PG·택배·Clock에만.**

### 13-4. BDD·Gherkin·Cucumber — 언제 가치가 있나

**BDD = Given(상황)/When(사건)/Then(결과)으로 행위를 도메인 언어로 기술.** TDD가 "단위 불변식", BDD가 "사용자/도메인 시나리오"입니다.

- **Gherkin** = 그 Given/When/Then을 거의 평문으로 쓰는 *문법(언어)*. 프로그래밍 언어가 아니라 기획자·정산팀·개발자가 *같은 문장*을 읽습니다.
- **Cucumber**(자바) = Gherkin 평문을 실제 코드(글루)에 연결해 *실행*해주는 프레임워크. `@When("…환불하면")` → 실제 서비스 호출, `@Then("net은 {int}원")` → 실제 검증. (유사: JBehave, Spock.)

```gherkin
Scenario: 단순변심 카드 환불은 판매수수료가 환원된다     # §6 조합표를 그대로 옮긴 것
  Given 셀러 A가 카드로 10,000원 상품을 판매했고
    And 정산 라인에 SALE(+10,000), COMMISSION(-1,000)이 있을 때
  When  구매자가 단순변심으로 전량 환불하면
  Then  REFUND(-10,000)와 REFUND_COMMISSION(+1,000) 라인이 생기고
    And 셀러 A의 net 정산액은 0원이다
```

> **AI 관점:** 이 Gherkin을 AI에게 주면 "환원 규칙 6개"가 모호함 없이 박혀 자연어 프롬프트보다 정확한 명세가 됩니다.

**현실적 권고:** 대부분은 Cucumber까지 안 갑니다(`.feature` 파일·글루 관리 비용). **JUnit 안에서 `@Nested` + `@DisplayName("단순변심+카드면 판매수수료가 환원된다")`로 Given/When/Then *스타일*만 차용**하는 게 비용 대비 최적입니다. 별도 BDD 프레임워크는 **비개발자(기획·정산팀)가 직접 시나리오를 읽고 쓰고 검수하는 문화가 있을 때만**.

### 13-5. 테스트가 *구조적으로* 못 보는 것 + 3중 방어선

이 시스템 위험의 절반은 단위/통합 테스트가 못 봅니다. 시니어의 진짜 영역입니다.

| 테스트가 못 보는 것 | 왜 | 무엇으로 덮나 |
|---|---|---|
| **동시성/race**(마감 중 라인 유입, 따닥 결제) | 단위 테스트는 단일 스레드·결정론 | 동시성 테스트(`CountDownLatch` N스레드 동시 INSERT) + **DB 제약**(`FOR SHARE`·EXCLUDE)이 진짜 방어 + 부하 테스트 |
| **이벤트 순서 역전**(PAID 전 REFUNDED) | 테스트는 순서대로 보냄 | **결정론적 시뮬레이션**(이벤트를 일부러 뒤섞어 주입) + 파티션키·상태머신 가드(§5) |
| **돈 1원 드리프트**(장기 누적) | 케이스 몇 개론 안 드러남 | **Property-based test**(랜덤 1만 시나리오 → "net=SUM 항등식 항상 성립") + **런타임 4-way 대사**(§4-4) |
| **at-least-once 중복** | 단위 테스트는 1번만 보냄 | "같은 `source_event_id` 재INSERT → 23505 swallow+ack" 명시 테스트 + 대사 |
| **외부 PG 실제 동작 변화** | mock은 *내가 상상한* 응답만 | **Contract test**(Pact류) + 스테이징 실 PG + production 대사 |
| **장기 운영**(정산 미아·추적불가 fallback) | 테스트는 "지금" | **시계 조작 테스트**(Clock 주입해 D+28 점프) + 운영 대시보드 알람 |

#### 핵심 원리 — 검증을 "테스트 시점"에서 "런타임"으로 옮긴다

**DB 불변식(DB invariant)** = 검증을 애플리케이션 코드가 아니라 **DB 제약으로 박아, 잘못된 데이터는 INSERT 자체가 거부되게** 하는 것(`ck_settline_sign`·EXCLUDE·부분 UNIQUE). 코드 검증은 "그 경로를 지날 때만" 작동하지만 **DB 제약은 데이터가 들어오는 모든 문**을 지킵니다(AI가 우회 경로를 새로 짜도 못 뚫음).

**런타임 대사(reconciliation)** = 독립적으로 계산된 두(이상의) 숫자를 맞춰보고 다르면 알람(회계의 "장부 맞추기"). 테스트는 *내가 상상한 케이스*만 보지만, 대사는 *실제로 일어난 모든 거래*를 검증해 **"모르는 버그가 만든 1원 차이"**까지 잡습니다.

```
4-way 대사(§4-4) = 같은 돈을 4경로로 계산해 전부 일치하는지
  ① settlement_line SUM   ② pg_settlement_raw(PG 입금파일)
  ③ 복식부기 원장(CLEARING_PG 잔액=0)   ④ 은행 실입금
  하나라도 어긋나면 = break → 알람 → 사람 조사
```

#### 3중 방어선 (이 절의 결론 그림)

```
[1] 배포 전 ─── 테스트(TDD/통합)        "알려진 불변식을 미리 막는다"
       예: 환불은 음수다, over-ship 거부
        ↓ 통과해도 못 믿는 영역(동시성·미지 버그)이 남음
[2] INSERT 시점 ─── DB 불변식(CHECK/UNIQUE/EXCLUDE)  "잘못된 데이터는 들어오는 순간 거부"
       예: ck_settline_sign이 양수 REFUND를 23514로 거부
        ↓ DB가 못 보는 "관계의 정합"(PG가 실제 줬나)이 남음
[3] 런타임 주기 ─── 대사 배치(§4-4·§8)  "독립 계산을 맞춰 어긋나면 알람"
       예: 원장 SUM ≠ PG 입금파일 → break 알람
```

> **시니어 운영 관점(결론):** 토스·배민이 정산에서 이 3중 구조를 쓰는 철학 — **테스트는 "내가 아는 버그"를 막고, DB 불변식은 "모든 경로"를 지키며, 대사는 "내가 모르는 버그가 만든 차이"를 사후에 잡습니다.** AI 코드엔 "모르는 버그"가 더 많으므로 [2]·[3]의 가치가 AI 시대에 *오히려 커집니다*. 사람이 코드 리뷰로 "그럴듯함"을 믿는 대신, **DB가 거부하고 대사가 맞는 것으로** 믿습니다.

#### 깊게 — 각 기법을 *실제로 어떻게* 만드나

위 표·방어선은 "무엇으로 덮나"까지였습니다. 여기서는 그 기법들을 **실제로 어떻게 짓는지**를 봅니다. 출발점은 한 가지 — **예제 테스트(TDD/BDD)는 "내가 생각해낸 입력"이라는 점(點)만 찍습니다.** 진짜 버그는 내가 생각 못 한 입력·순서·타이밍에 있고, AI 코드엔 그런 사각지대가 더 많습니다. 아래 기법들은 그 빈 공간을 **열거하지 않고** 덮습니다.

```
   [ 입력/상태 공간 전체 ]
   TDD/BDD 예제 ── 내가 생각한 케이스        (●  점)
   ① Property   ── 생각 못 한 *입력*까지      (면)   ┐ 배포 전(CI):
   ② 결정론 시뮬 ── 생각 못 한 *순서·장애*까지  (시간축) ┘ 공간을 폭격
   ③ 런타임 대사 ── 프로덕션 *실데이터*의 사각  (상시)  ┐ 배포 후:
   ④ DB 불변식  ── 그래도 새어나온 것을 물리 차단      ┘ 살아남은 것
```

<details>
<summary>① <b>Property-based test — "예시" 대신 "법칙"을 검증, 그리고 stateful(model-based)</b></summary>

**발상의 전환**: `입력 X → 출력 Y`를 적는 대신, **모든 입력에 항상 참인 명제(property)** 를 적습니다. 프레임워크가 입력을 수백~수천 개 **랜덤 생성**해 그 명제를 깨려 시도하고, 깨지면 **shrinking** 으로 최소 반례까지 줄여줍니다(예: "환불 17번+8347원"에서 깨졌는데 → "환불 2번+1원"으로 최소화해 보여줌).

**어떤 "법칙"을 적나** (이 시스템 기준):

| 법칙 종류 | 이 시스템의 불변식 |
|---|---|
| **보존(conservation)** | `Σ(셀러별 정산액) + 플랫폼 수수료 == 주문 총액` — 1원도 새지 않음 |
| **경계** | 어떤 환불 조합을 넣어도 `0 ≤ 누적환불 ≤ 결제금액` |
| **멱등성** | 같은 멱등키로 환불 2번 = 1번과 동일한 최종 상태 |
| **단조성** | 누적 환불액·정산 라인은 늘기만, 절대 줄지 않음(append-only) |

가장 강력한 건 **Stateful / Model-based PBT** 입니다. 프레임워크가 **랜덤 명령 시퀀스**(`주문 → 부분환불 → 부분환불 → 취소 → 환불 → …`)를 만들어 **(a) 실제 시스템** 과 **(b) 아주 단순한 참조 모델(머릿속 계산기)** 양쪽에 똑같이 돌리고, **매 스텝마다 두 상태가 일치하는지** 대조합니다. 환불 조합·멀티셀러 같은 **상태기계 버그**를 잡는 핵심입니다.

```java
// jqwik 의사코드 — 멀티셀러 정산 "돈 보존" 법칙
@Property
void 정산은_총액을_보존한다(@ForAll @Size(min=1,max=20) List<OrderLine> lines) {
    Settlement s = settlementService.calculate(lines);
    long sellerSum = s.perSeller().values().stream().mapToLong(v->v).sum();
    assertThat(sellerSum + s.platformFee())
        .isEqualTo(lines.stream().mapToLong(OrderLine::amount).sum());
    // → 셀러별 반올림이 누적돼 1원 증발하는 케이스를 랜덤 금액 조합이 찾아냄
}
```

도구: jqwik(Java)·Hypothesis(Python)·fast-check(TS). **시니어 운영 관점**: 정산·환불처럼 "돈 항등식"이 있는 곳이 PBT의 1순위 — 케이스를 사람이 못 열거하는 바로 그 영역이라서.

</details>

<details>
<summary>② <b>결정론적 시뮬레이션(DST) — 순서·동시성·장애를 시드로 통제해 폭격(TigerBeetle/FoundationDB식)</b></summary>

PBT가 **입력**을 흔든다면, DST는 **시간·동시성·장애**를 흔듭니다.

**핵심 아이디어**: 비결정성의 원천을 **전부 주입(inject) 가능**하게 만든 뒤 **시드(seed)로 고정**합니다.
- 시계 → `Clock` 인터페이스(절대 `System.now()` 직접 호출 금지, §13-5 표의 "시계 조작")
- 난수 → 시드 받은 RNG
- 외부 호출(PG·택배) → 시뮬레이터가 응답 시점·성공/실패/지연을 제어
- (본격판) 스레드 스케줄링까지 단일 이벤트 루프로 통제

그러면 **가속된 시간**(7일 정산 사이클을 1초에), **장애 주입**(커밋 직전 크래시·메시지 순서 역전·이벤트 중복 발행), 그리고 무엇보다 **완벽한 재현**(깨지면 시드 한 개만 남기면 한 치도 다르지 않게 재현 — heisenbug를 결정론적 버그로) 이 가능해집니다.

**현실적으로 어디까지 만드나** — TigerBeetle 풀버전은 불필요합니다. 이 시스템의 실용적 중간 지점:

> **시나리오 시뮬레이터**: 도메인을 시드 기반 랜덤 이벤트 시퀀스로 구동하되, **mock 친 외부 경계(PG·택배·Clock)에만 장애를 주입**하고 **매 스텝 후 불변식을 검사**한다. DB는 Testcontainers 실 DB 그대로(§13-3 원칙 유지).

```
seed=42 →
  t=0   주문 생성
  t=10  부분환불 5천 요청
  t=10  부분환불 3천 요청 (같은 틱! 동시성)
  t=11  [장애주입] PG 성공응답 후 커밋 전 크래시
  t=20  재기동 → outbox 재처리(at-least-once)
  ───── assert: 누적환불==8천, 이벤트 중복발행 0, settlement_line SUM 정합
```

CI에서 **수천 개 시드**를 돌리고 깨진 시드만 이슈에 붙입니다. "동시 환불 + 크래시 + 재처리" 조합은 사람이 손으로 못 짜지만 시뮬레이터는 시드만 바꿔 무한히 만듭니다.

</details>

<details>
<summary>③ <b>런타임 대사(reconciliation) — 만드는 법 + "대사"라는 용어 (실무 표준어)</b></summary>

**용어부터** — **대사(對査)** = 서로 대(對)하여 조사(査)함 = 두 장부/숫자를 맞대어 일치 검증. 영어 **reconciliation**의 번역어로, 회계의 *은행 대사(bank reconciliation)* 가 원조입니다. **결제·정산 도메인에선 표준어**라 PG사·커머스엔 "대사팀"·`payment-reconciliation-batch` 같은 명명이 흔합니다. 단, 재고·이벤트처럼 결제 밖 도메인에 빌려 쓸 땐 의미상 **"정합성 검증(consistency check)"** 에 가까우므로, 동료와 말할 땐 **"정합성 대사"** 로 풀거나 **"대사(reconciliation)"** 로 영어를 병기하면 오해가 없습니다.

**만드는 법의 제1원칙**: 대사는 데이터를 만든 코드와 **반드시 다른 경로**로 재계산해야 합니다. 같은 코드로 검산하면 같은 버그가 양쪽에 숨습니다. 테스트는 배포 전 한 번이지만 **진짜 엣지 데이터는 프로덕션에만** 있고, 대사는 그걸 상시로 잡습니다.

| 대사 종류 | 무엇 ↔ 무엇 (독립 경로) | 불일치가 뜻하는 것 |
|---|---|---|
| **결제/정산 대사** | 원장 SUM ↔ PG 입금파일 ↔ 복식부기 잔액 ↔ 은행 실입금 (§4-4 4-way) | 누락·중복·반올림 버그 |
| **재고 대사** | 논리재고(주문 차감 누계) ↔ WMS 실재고 | 오버셀·재고 음수 |
| **이벤트 대사** | outbox 적재 건수 ↔ 실제 발행 건수 | dual-write 깨짐·이벤트 유실(§5) |

```
매일 03:00 결제대사:
  our_total = SELECT sum(amount) FROM payments WHERE d=어제 AND status='paid'
  pg_total  = PG 정산파일 파싱 합계               ← 독립 경로
  drift     = our_total - pg_total
  emit metric payment.recon.drift = drift          ← 대시보드에 항상 0인지 본다
  if drift != 0 → 슬랙 알림 + 차이나는 주문 목록 첨부
```

**시니어 운영 관점**: drift 메트릭을 **그래프로 상시 0인지 지켜보는 것** 자체가 안전망입니다. 0에서 벗어나는 순간이 "내가 모르던 버그"의 첫 신호입니다.

</details>

<details>
<summary>④ <b>DB 불변식 + 런타임 단언 — AI(와 모든 코드)가 못 어기는 하드월</b></summary>

이게 §13-5 [2]의 구현 디테일이자 "박제(못 어기게)"의 마지막 보루입니다. 앱 코드가 어떤 경로로 쓰든, 어떤 버그가 있든 **DB가 거부**합니다 — AI가 짠 코드의 *조용한 데이터 오염*을 *시끄러운 트랜잭션 실패*로 바꿔줍니다.

```sql
-- 환불액은 결제액을 못 넘고 음수도 불가
ALTER TABLE payment ADD CONSTRAINT chk_refund
  CHECK (refunded_amount >= 0 AND refunded_amount <= paid_amount);

-- 멱등키로 중복 환불 물리 차단
CREATE UNIQUE INDEX uq_refund_idem ON refund(idempotency_key);

-- 한정수량·좌석 double-booking 차단 (Postgres EXCLUDE)
ALTER TABLE reservation ADD CONSTRAINT no_overlap
  EXCLUDE USING gist (seat_id WITH =, period WITH &&);
```

여기에 **코드 레벨 런타임 단언**을 더합니다 — 애그리거트를 저장/로드할 때마다 불변식을 `assert`하고, 깨지면 조용히 넘어가지 말고 **즉시 throw + 알림**(TigerBeetle의 "assert everything"). "여기까지 왔으면 이 조건은 반드시 참"을 프로덕션에서 강제하므로, AI가 못 본 경로로 진입하면 거기서 멈춥니다.

</details>

### 13-6. AI로 이 시스템을 짜는 실전 레시피 (종합)

```
1. [DDD] 경계·aggregate·불변식 목록을 먼저 확정 (이 문서가 이미 ~80% 함)
        → 불변식을 타입·DB제약·private 생성자로 박제 = AI가 못 어기게
        → 컨텍스트 1개 = AI 작업 1단위 (한 번에 하나, 남의 내부는 이벤트 페이로드만)
2. [TDD] 돈·정산·멱등·상태머신은 테스트 먼저
        → 테스트 = AI에게 주는 검증 가능한 명세이자 채점 기준
        → "이 테스트들을 통과시켜라" (모호한 "좋은 코드 짜줘" 금지)
        → 단언(assert)은 사람이 검수 (테스트가 명세이므로)
3. [Mock] DB·도메인 = 진짜(Testcontainers). PG·택배·Clock = mock.
        → "상태 검증, verify(interaction) 금지" 명시 (AI의 mock 남발 억제)
4. [BDD 스타일] 환불 조합·멀티셀러 같은 비즈 규칙은 Given/When/Then 네이밍
        → 별도 프레임워크 X, @Nested + @DisplayName
5. [테스트 너머] 동시성·순서·드리프트 = 동시성 테스트 + property + 결정론적 재배치
        → 그리고 무엇보다 런타임 대사·DB 불변식 (테스트가 아니라 상시 가드)
6. [AI 검증 루프] AI 코드를 "리뷰로" 믿지 말 것 →
        테스트 그린 + 대사 통과 + DB 불변식 위반 0 으로 믿을 것.
        "그럴듯함"은 AI가 가장 잘하는 거짓말.
```

**백지 복원용 한 줄:**

```
DDD로 AI가 넘으면 안 되는 선(불변식)을 코드·타입·DB로 박제하고, TDD로 그 선을 검증 가능한
명세로 만들어 AI에게 채점 기준으로 주며, Mock은 외부 경계(PG·택배·시계)에만 치고 DB·도메인은
진짜로 테스트한다. 테스트가 못 보는 동시성·순서·돈 드리프트는 property 테스트와 런타임 대사·
DB 불변식으로 덮는다 — AI 코드엔 모르는 버그가 더 많으므로 이 런타임 안전망의 가치가 더 커진다.
방어 3중: 테스트(아는 버그)·DB 불변식(모든 경로)·대사(모르는 버그가 만든 차이).
```
