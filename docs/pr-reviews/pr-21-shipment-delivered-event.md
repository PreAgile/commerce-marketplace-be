# PR #21 리뷰 정리 — 배송완료 → 정산-가능 이벤트 발행 (M4-a)

> 대상 PR: `feat(shipping): 배송완료 → 정산-가능 이벤트 발행 (트랜잭셔널 아웃박스, M4-a)`
> 리뷰어: Gemini Code Assist(5건, 전부 medium) · CodeRabbit(재트리거 후 actionable 4건, 전부 테스트 강화 — 아래 CodeRabbit 섹션) · CI Build & Test(pass)
> 이 문서는 봇 리뷰를 AGENTS.md 규칙에 비춰 **적대적으로 재판정**한 결과다(§4). 지적을 그대로 수용하지 않고, 사실관계와 트레이드오프를 검증한다.

관련 파일: `shipping/application/ShipmentService.java` · 스키마 `db/migration/V9__outbox.sql`

---

## 처리 요약

| # | 이슈 | 판정 | 근거 |
|---|------|------|------|
| 1 | 주석의 산문 틱(엠대시·괄호 보충) — lines 81, 90, 130 | ✅ 수용 | AGENTS.md §6.5 위반은 내 새 주석의 실제 문제. "왜"는 유지하고 평서문으로 정리 |
| 2 | `new ObjectMapper()` 대신 Spring 빈 주입 — lines 46, 138 | 🤔 보류/거절 | Boot 4에서 `com.fasterxml` ObjectMapper 빈 미노출(런타임 전용), 3곳 확립 패턴, payload가 primitive라 공유 설정 무관 |
| — | line 46 주석의 사실관계("빈을 노출하지 않는다") | ⚠️ 문구 정정 | 표현이 부정확 — "런타임에만 노출"이 정확(build.gradle.kts:27) |

> 위 표는 **Gemini** 5건. **CodeRabbit** 4건(테스트 강화, 전부 수용)은 아래 별도 섹션 참조.
> **CI**: Build & Test(Testcontainers 실 DB) **pass**. Gemini는 정확성 회귀 0건(주석 스타일·의존성 취향), CodeRabbit은 테스트 커버리지 강화에 집중.

---

## 1. 주석의 산문 틱 — AGENTS.md §6.5 위반 (✅ 수용)

**어디서:** `ShipmentService.java` lines 81(javadoc), 90(라인주석), 130(라인주석). Gemini가 3건으로 지적.

**원인:** 내가 이번 PR에서 추가한 주석들이 **엠대시(—)와 괄호 보충설명**을 반복 사용했다. 예:
- line 81: `...정확히 1회다 (payment의 isPaid no-op과 같은 원리 — uq_outbox_event는 최후 보루).`
- line 90: `...함께 읽는다(DELIVERED 이벤트 payload가 셀러 귀속에 쓴다).`
- line 130: `...ISO-8601 문자열로 싣는다(published 경계는 primitive — jsr310 모듈 의존을 피하고 소비자 파싱이 단순).`

AGENTS.md §6.5는 명시적으로 금지한다: *"산문 틱 금지. 과한 엠대시(—), 괄호 보충설명 남발, `*별표강조*`, 번역투. 평서문 한 줄로."* Gemini는 저장소 자신의 규칙(`.gemini/styleguide.md`에도 동일 규율)을 그대로 집행했다.

**판정: 타당. 수용.** 내 새 주석의 실제 규율 위반이다. 다만 **"왜"(트레이드오프·함정)는 살릴 가치가 있으므로 지우지 말고**(§6.6·§6.7), 엠대시·괄호를 걷어내고 평서문으로 다듬는다.

> 코드베이스 전반(기존 주석·ADR)도 엠대시를 널리 쓰는 규칙-관행 간 긴장이 있다. 기존 코드를 일괄 리포맷하지는 않고, **이번 diff에서 내가 새로 넣은 주석만** 규칙에 맞춘다(잡음 최소화, §6.1).

**해결책(적용 시):**
- line 81 javadoc: `DELIVERED 도달 시 정산-가능 신호(ShipmentDelivered)를 같은 트랜잭션으로 outbox에 적재한다. DELIVERED는 종료 상태라 상태머신이 재진입을 막고 핫로우 락이 동시 전이를 직렬화하므로 이벤트는 배송당 정확히 1회다.` (엠대시·괄호 제거, 두 문장)
- line 90: `order_id·seller_id도 이 잠금에서 함께 읽는다. DELIVERED 이벤트 payload의 셀러 귀속에 쓴다.`
- line 130: `deliveredAt은 ISO-8601 문자열로 싣는다. published 경계는 primitive라 소비자 jsr310 모듈 의존을 피하고 파싱이 단순하다.`

---

## 2. `new ObjectMapper()` 대신 Spring `ObjectMapper` 빈 주입 (🤔 보류/거절)

**어디서:** `ShipmentService.java` line 36(`private static final ObjectMapper JSON = new ObjectMapper();`)과 line 133(`JSON.writeValueAsString(...)`). Gemini는 생성자 주입으로 바꾸라고 제안(comments 1·5).

**Gemini 주장:** "Spring Boot는 `JacksonAutoConfiguration`으로 `ObjectMapper` 빈을 자동 구성한다. `new`로 직접 만들면 전역 Jackson 설정(네이밍 전략, 미지정 필드 무시 등)을 공유하지 못해 일관성이 깨진다."

**적대적 검증:**
1. **사실관계 — Boot 4에서 이 주장은 부정확할 공산이 크다.** 이 저장소는 `spring-boot-starter-webmvc`(Boot 4)를 쓰고, `build.gradle.kts:27`이 명시한다: *"webmvc 스타터는 Jackson을 런타임에만 노출한다 — outbox payload 직렬화는 컴파일 의존이 필요해 명시 추가."* Boot 4 웹 스택은 Jackson 3(`tools.jackson`)로 이동했고, 클래식 `com.fasterxml.jackson.databind.ObjectMapper`(Jackson 2) 빈이 자동 등록된다는 보장이 없다. Gemini는 **Boot 3 지식으로 추론**한 것으로 보인다.
2. **확립된 패턴 — 3곳이 동일하게 `new ObjectMapper()`를 쓴다**: `PaymentService`(M2, ADR-013), `shipping.PaymentConfirmedHandler`, 이번 `ShipmentService`. M2가 Boot 4에서 의도적으로 택한 결정이다. ShipmentService만 주입으로 바꾸면 **일관성이 깨지고**, 주입 대상 빈이 실제로 없으면 컨텍스트 기동이 깨진다.
3. **효용 — 이 payload엔 이득이 없다**: 이벤트 payload는 `long`×3 + ISO-8601 문자열 1개다. 외부 신뢰불가 문자열(payment의 `pgTransactionId` 같은)이 없고, 네이밍 전략·미지정 필드 정책이 결과를 바꾸지 않는다. 공유 설정을 받아봐야 직렬화 결과가 동일하다.

**판정: 이 PR에서는 거절(보류).** 근거: (1) Boot 4에서 주입 가능 여부 미확인(오히려 미노출 정황), (2) 3곳 확립 패턴과의 일관성, (3) primitive payload라 공유 설정 무관. **주입으로 가려면** 컨텍스트 테스트로 `com.fasterxml` ObjectMapper 빈 존재를 실증한 뒤 **3곳을 함께 바꾸는 별도 리팩터**로 해야 한다(이 슬라이스 범위 밖).

**단, 문구는 정정한다(⚠️):** line 34 주석 `"webmvc 스타터는 일반 ObjectMapper 빈을 노출하지 않는다"`는 표현이 부정확하다. 정확히는 **"Jackson을 런타임 의존으로만 가져와, 직렬화 전용 인스턴스를 직접 보유한다"**(build.gradle.kts:27과 일치). PaymentService의 같은 주석도 동일하게 다듬는 게 맞다(별도 정리).

---

## CodeRabbit — 재트리거 후 리뷰 (actionable 4건)

CodeRabbit은 최초 커밋에서 **리뷰 한도 초과**로 스킵됐다("Review limit reached"). 한도 해제 후 `@coderabbitai review`로 재트리거해 결과를 수집했다. **정확성 회귀는 0건, 4건 모두 테스트 강화 계열**이고 Gemini와 겹치지 않는다. 판정은 이 저장소의 테스트 규율(§2 — 실 DB·상태머신 가드·반례·동어반복 금지)에 정확히 부합한다.

| # | 이슈 | 심각도 | 판정 |
|---|------|--------|------|
| CR-1 | `seedOutForDelivery` 픽스처가 상태머신을 우회(행 직접 INSERT) | Major | ✅ 수용 |
| CR-2 | 동시성 테스트가 패자 스레드 결과를 집계 안 함(예상 밖 예외 누락) | Major | ✅ 수용 |
| CR-3 | `ShipmentDelivered` payload 계약을 끝까지 안 봄(shipmentId·deliveredAt 미검증) | Minor | ✅ 수용 |
| CR-4 | `intermediatePublishesNothing`이 OUT_FOR_DELIVERY 직전 상태를 안 봄 | Minor | ✅ 수용 |

### CR-1. 픽스처를 상태머신 경로로 (✅)

**원인:** `ShipmentTransitionConcurrencyIT.seedOutForDelivery`가 `shipment_event`를 `sort_key=3` 한 건만 직접 INSERT해 OUT_FOR_DELIVERY를 만든다. READY→PICKED_UP→IN_TRANSIT 이력이 없는 **비현실적 상태**이고, 상태머신을 우회한다. 경로 지침(`**/{order,shipping}/**` "상태 전이가 상태머신으로 가드되는지", `**/src/test/**` "DB 도메인 로직 우회 금지")과 어긋난다.

**판정: 수용.** 픽스처도 도메인 전이(`recordTransition`)로 쌓으면 실제 이력이 생기고 상태머신 회귀까지 함께 방어한다. 부수효과 없음(OUT_FOR_DELIVERY 전이는 이벤트를 발행하지 않아 race 전 outbox가 깨끗).

**해결책:** `seedReadyShipment()` → `recordTransition(PICKED_UP)` → `(IN_TRANSIT)` → `(OUT_FOR_DELIVERY)`로 픽스처 구성.

### CR-2. 패자 스레드 결과를 반드시 집계 (✅ — PR #20 #5와 같은 결)

**원인:** 동시 DELIVERED 테스트가 `IllegalStateException`을 카운트하지 않고 `Future`도 버린다. 그래서 패자 15스레드가 **예상 밖 예외로 죽어도** `success==1`·`outbox==1`이면 테스트가 통과한다 — "그럴듯하지만 덜 검증하는" 구멍. PR #20에서 잡았던 #5(동어반복 테스트)와 정확히 같은 계열.

**판정: 수용.** 이 저장소 동시성 규율(§2, CountDownLatch로 실제 경합 재현 + 아무것도 검증 안 하는 테스트 배격)에 부합.

**해결책:** `rejected` 카운터 추가 + 예상 밖 `Throwable`을 `AtomicReference`로 포착 → 끝에서 `rejected == threads-1` 그리고 `unexpected == null` 단언. 그러면 패자가 자가전이(ISE)가 아닌 다른 이유로 실패하면 빨개진다.

### CR-3. payload 계약을 끝까지 검증 (✅)

**원인:** `deliveredPublishes`가 `orderId`·`sellerId`만 확인한다. `shipmentId` 누락이나 `deliveredAt`이 **DB의 DELIVERED 발생시각과 다른** 회귀를 놓친다. 이 PR의 계약은 payload 4필드(ADR-016)라 계약 전체를 봐야 한다.

**판정: 수용.** payload는 M4-b 소비자가 의존할 published 계약이라, 필드 누락·잘못된 timestamp는 하류 정산 버그로 번진다.

**해결책:** `(payload->>'shipmentId')::bigint == id` 그리고 `payload->>'deliveredAt' == shipment_event의 DELIVERED occurred_at.toString()` 단언 추가.

### CR-4. OUT_FOR_DELIVERY에서도 미발행 확인 (✅)

**원인:** `intermediatePublishesNothing`이 PICKED_UP·IN_TRANSIT까지만 본다. **DELIVERED 직전 상태(OUT_FOR_DELIVERY)에서 조기 발행되는 회귀**가 가장 위험한데 그 케이스가 빠졌다.

**판정: 수용.** 경계값(직전 상태)이 negative 테스트의 핵심이다.

**해결책:** 해당 테스트에 `transition(OUT_FOR_DELIVERY)` 한 줄 추가 후 `deliveredCount == 0` 유지 확인.

### (참고) Duplicate — "M4-b 없으면 릴레이가 선소모" (Major, Heavy lift)

CodeRabbit이 중복으로 표시한 우려: settlement 소비자가 아직 없는데 릴레이가 켜져 있으면, 디스패처가 `ShipmentDelivered`를 집어 **매칭 핸들러 없이 published로 마킹** → 나중에 M4-b 핸들러가 추가돼도 재처리 안 돼 **정산 신호 유실** 가능.

**판정: 현재 안전, 단 M4-b 진입 시 주의.** 릴레이는 `@ConditionalOnProperty(outbox.relay.enabled)`로 **기본 비활성**(ADR-014, 테스트는 디스패처 직접 호출). 따라서 M4-a 단독 배포에서 선소모는 일어나지 않는다. **M4-b에서 핸들러 등록과 릴레이 활성화를 같은 슬라이스로 묶어야** 한다 — 핸들러 없이 릴레이만 켜는 배포를 금지. (디스패처가 no-handler를 어떻게 처리하는지 M4-b에서 재검증 항목으로 남긴다.)

---

> **종합 판정:** CodeRabbit 4건은 전부 **수용**(테스트 강화, 로직 변경 없음). Gemini와 달리 정확성/회귀 방어를 파고들었고 이 저장소의 테스트 규율과 정확히 맞는다. CR-2는 PR #20 #5의 재발 방지 연장선이다.

---

## 결론

- **Gemini(5건)**: 정확성 회귀 0건 — 3건 주석 산문 틱(수용, §6.5), 2건 ObjectMapper 주입(Boot 4 사실관계·일관성·효용 근거로 거절).
- **CodeRabbit(4건)**: 전부 **테스트 강화 수용** — 픽스처 상태머신화(CR-1), 패자 스레드 집계(CR-2, PR #20 #5 연장), payload 계약 완전 검증(CR-3), OUT_FOR_DELIVERY 미발행 확인(CR-4).
- **적용 범위**: 프로덕션 로직 변경 없음. 바뀌는 건 **주석 정리(§6.5) + 테스트 4곳 강화**뿐. 별도 우려(릴레이 선소모)는 M4-b에서 핸들러+릴레이 활성화를 한 슬라이스로 묶어 해소.

---

## 심화 방어 — "왜 하필 outbox 패턴인가?" (꼬리질문)

> 리뷰어/면접관이 이 PR의 이벤트 발행 방식을 파고들 때를 상정한 깊이 있는 방어. 각 토글은 하나의 꼬리질문과 그 답이다. 판정 기준은 이 아키텍처의 실제 제약 — **단일 Postgres · 모듈러 모놀리스(ADR-004) · 아직 브로커 없음 · 돈 불변식 우선**.

<details>
<summary><b>Q0. 애초에 무슨 문제를 푸는 건가? "dual-write 문제"가 뭔데?</b></summary>

`recordTransition`이 DELIVERED에서 해야 할 일은 두 가지다: (1) 배송 상태를 **DB에 쓴다**, (2) "이 배송이 완료됐다"는 사실을 **정산이 알게 한다**. 이 둘이 서로 다른 시스템(DB + 메시지 브로커)이면, 둘을 하나로 원자적으로 커밋할 방법이 없다. 그래서 생기는 두 실패 모드가 곧 문제의 핵심이다:

- **커밋 후 발행 실패**: DB에 DELIVERED를 쓰고 커밋 → 발행 직전 크래시 → 이벤트 유실 → 정산이 영영 안 돎 → **셀러 미정산(돈 안 줌)**.
- **발행 후 커밋 실패**: 이벤트를 먼저 쏘고 → DB 트랜잭션 롤백 → 배송은 완료가 아닌데 정산은 진행 → **미배송 건 정산(돈 잘못 줌)**.

둘 다 돈 불변식을 깬다. "두 저장소에 나눠 쓰는데 원자성이 없다" — 이게 dual-write 문제이고, 이 PR이 방어하는 대상이다.

</details>

<details>
<summary><b>Q1. 그래서 outbox가 이걸 어떻게 푸나?</b></summary>

**이벤트를 별도 시스템이 아니라 도메인과 같은 DB, 같은 트랜잭션에 쓴다.** `outbox` 행 INSERT가 `shipment`/`shipment_event` 쓰기와 **하나의 로컬 ACID 트랜잭션**에 들어간다(ADR-002). 그러면:

- 원자성이 **분산 원자성이 아니라 DB 로컬 트랜잭션**으로 환원된다 — 둘 다 커밋되거나 둘 다 롤백. 중간 상태가 원천적으로 불가능.
- "발행 결정"과 "실제 발행"이 분리된다. 커밋된 outbox 행을 **릴레이가 나중에 읽어** 발행한다(at-least-once). 발행이 늦거나 재시도돼도, "발행해야 한다는 사실"은 이미 내구성 있게 커밋돼 있어 유실되지 않는다.

이 PR에서 append를 DELIVERED 전이와 **같은 트랜잭션**에 넣은 게 정확히 이 지점이다(코드 line 121-124).

<blockquote>

🔬 <b>더 깊이 — 근본 이유와 "단일 DB면 그냥 한 Tx면 되잖아?"</b> (아래 중첩 토글)

<details>
<summary><b>Q1-a. outbox의 근본 이유 — ① 원자성 ② 경계 (둘이 얽혀 있다)</b></summary>

전파를 outbox로 하는 이유는 하나가 아니라 둘이다.

- **① dual-write 원자성 (더 깊은 뿌리)**: 한 트랜잭션은 한 저장소의 로컬 원자성만 보장한다. "상태 변경"과 "그 사실의 외부 전파"를 서로 다른 시스템(DB + 브로커)으로 하면 원자적으로 못 묶는다 → 유실/유령 이벤트. DDD와 무관하게 단일 서비스 안에서도 생기는 문제.
- **② DDD 경계**: 전파를 다른 BC 테이블 직접 INSERT나 동기 호출로 하면 경계가 무너지고 가용성이 결합된다 → 전파는 이벤트로.

DELIVERED → 정산 예시로 네 방법 비교:

| 방법 | 무슨 일 | 왜 깨지나 |
|---|---|---|
| A. settlement 테이블 직접 INSERT | shipping이 `settlement_line`에 씀 | ② 위반 — 내부 스키마 결합, 경계 붕괴 |
| B. settlement 동기 호출 | 같은 tx에서 `settlementService.create(...)` | ② 위반 — settlement 죽으면 배송도 실패 |
| C. 커밋 후 브로커 발행 | `commit()` 뒤 `kafka.publish()` | ① 위반 — 커밋·발행 사이 크래시 → 미정산 |
| D. outbox ✅ | 자기 테이블만(`shipment`+`outbox`) 한 로컬 tx | ① 해결(로컬 ACID) + ② 해결(settlement 모름) |

D의 트릭: "전파할 사실"을 브로커가 아니라 **같은 DB의 outbox 행으로 바꿔** 로컬 트랜잭션 하나로 환원한다.

</details>

<details>
<summary><b>Q1-b. ⭐ 단일 DB 모놀리스면 경계 무시하고 그냥 한 Tx로 묶으면 문제없지 않나?</b></summary>

**원자성만 놓고 보면 맞다. 이 구성에선 한 Tx로 묶으면 원자성은 공짜다.** shipping이 `settlement_line`을 같은 `@Transactional`에서 직접 INSERT하면 둘 다 커밋되거나 롤백된다. 게다가 지금은 브로커도 없고 릴레이·핸들러가 같은 JVM·같은 Postgres 안이라, **①(dual-write 원자성) 문제는 물리적으로 아직 존재하지도 않는다.** 직관이 정확하다.

그래서 이 구성에서 outbox의 진짜 이유는 **①이 아니라 ② + 진화 옵션 + 격리**다:

- **경계 결합(유지보수)**: 한 Tx로 묶으면 shipping이 settlement의 스키마·수수료 로직을 알게 된다. settlement 내부 변경이 shipping을 깬다. 모듈러 모놀리스의 존재 이유(독립 진화)를 스스로 무효화 — AGENTS.md §1 위반.
- **책임 응집 붕괴**: 수수료율·SALE/COMMISSION 규칙(settlement 것)이 shipping 전이 코드에 섞인다 → big ball of mud.
- **트랜잭션·락 확대(성능)**: 한 tx가 두 BC 쓰기를 품어 shipment `FOR UPDATE` 락을 settlement의 (느릴 수 있는) 수수료 계산 동안 잡는다 → 핫로우 경합 악화.
- **장애 격리 상실**: settlement 로직이 던지면 배송 DELIVERED 전이도 롤백된다. **정산 버그가 배송을 막는다.** outbox는 릴레이가 별도 tx로 소비하므로, settlement 실패가 배송 커밋을 되돌리지 못하고 재시도로 격리된다.
- **진화 경로 봉쇄 (결정적)**: 이 모놀리스는 settlement를 나중에 별 프로세스+별 DB로 추출할 수 있게 설계됐다(ADR-004, 로드맵). 추출하는 순간 "한 큰 Tx"는 진짜 dual-write/2PC 문제로 변한다. 한 Tx 전제로 짰으면 그 모든 교차 쓰기를 다시 짜야 한다. 이벤트(outbox)로 짜뒀으면 settlement가 다른 프로세스에서 같은 이벤트를 소비하기만 하면 돼 **추출이 거의 공짜**다.

**대가**: outbox는 강한 일관성을 **최종 일관성**으로 바꾼다. 배송이 DELIVERED로 커밋된 뒤 정산이 도는 사이에 폴링 간격만큼 창이 생긴다. 정산은 사용자 대면 실시간이 아니라 이 지연이 허용되므로, "강한 일관성 → 결합·격리·진화 옹벽"의 트레이드가 남는 장사다.

**정직한 결론**: 지금 물리 구성에서 outbox는 *원자성 때문이 아니다*. **경계를 코드에 박제하고, 장애를 격리하고, 미래의 값싼 추출 옵션을 지금 확보하려고, 최종 일관성을 대가로** 택한 것이다(ADR-004 "측정이 정당화할 때만 분리, 단 이음매는 미리 깨끗이"와 정합). 원자성(①)은 브로커/멀티DB로 진화하는 순간 비로소 바이팅하는데, 그때 코드를 안 바꾸려고 지금부터 그 형태로 둔 것이다.

</details>

</blockquote>

</details>

<details>
<summary><b>Q2. 2PC(XA 분산 트랜잭션)로 하면 되지 않나? 왜 안 쓰나?</b></summary>

2PC는 코디네이터가 DB와 브로커에 prepare→commit 2단계로 원자 커밋을 강제한다. 이론상 dual-write를 "정공법"으로 푼다. 그런데 이 아키텍처에서는 최악의 선택이다:

- **블로킹·in-doubt**: 코디네이터가 prepare와 commit 사이에 죽으면 참여 리소스가 **잠긴 채(in-doubt) 매달린다**. 수동 개입 전까지 락·커넥션이 묶여 가용성이 무너진다 — 운영 악몽.
- **가용성 결합**: DB 커밋이 브로커의 prepare 성공에 묶인다. 즉 **브로커가 죽으면 배송 API도 실패**한다. 정산 파이프 장애가 배송 쓰기를 막는다 — 황금률 §1(BC 독립)에 정면으로 위배.
- **지연·경합 악화**: 2단계 왕복 + 락 유지 시간 증가. 이 프로젝트는 핫로우 `FOR UPDATE` 직렬화가 이미 병목인데, 그 위에 분산 락을 얹으면 처리량이 더 떨어진다.
- **현실적 지원**: Kafka는 XA를 사실상 지원하지 않는다. 게다가 **우린 아직 브로커 자체가 없다.** 2PC를 쓰려면 브로커 + XA 코디네이터를 새로 들여와 "2PC가 푸는 문제"를 일부러 만들어야 한다.

정리: 2PC는 강한 정합성을 주지만 **가용성·운영성·확장성을 팔아서** 산다. outbox는 로컬 트랜잭션으로 같은 정합성(effect 기준)을 얻으면서 그 대가를 치르지 않는다. 그래서 현대 이벤트 아키텍처의 표준은 2PC가 아니라 outbox/saga다.

<blockquote>

🔬 <b>더 깊이 — XA·2PC·코디네이터·TM의 실체</b> (아래 중첩 토글)

<details>
<summary><b>Q2-a. XA가 정확히 뭔가?</b></summary>

**XA는 "분산 트랜잭션 표준(스펙)"이다.** X/Open이 만든 규격으로, **2PC 프로토콜을 구현하기 위한 인터페이스**를 정의한다. 등장인물이 둘:

- **TM (Transaction Manager)** = 코디네이터. 분산 트랜잭션 전체를 지휘.
- **RM (Resource Manager)** = 실제 데이터를 가진 참여자(DB, 메시지 큐 등).

Java는 이 XA를 **JTA(Java Transaction API)**로 노출한다. 어떤 리소스가 "XA를 지원한다"는 건 `XAResource` 인터페이스(`prepare()`·`commit()`·`rollback()`·`forget()`)를 구현해 **외부 코디네이터의 지휘를 받을 수 있다**는 뜻이다.

</details>

<details>
<summary><b>Q2-b. 2PC 흐름과 치명적 함정(in-doubt)</b></summary>

커밋을 **두 단계로 쪼갠다**:

- **1단계 prepare/투표**: 코디네이터가 각 참여자에게 "커밋할 수 있나?" → 각자 작업+로그기록+락 잡고 "YES(준비됨)/NO" 투표.
- **2단계 commit/확정**: 전원 YES면 "전원 커밋", 하나라도 NO면 "전원 롤백".

결혼식 주례가 양쪽에 "이의 없으십니까?"를 물어 **둘 다 예**라야 성사시키는 것과 같다.

**함정**: 1단계에서 "YES"라고 투표한 참여자는 2단계 지시가 올 때까지 **커밋 가능한 상태로 락을 쥔 채 대기**해야 한다. 이때 **코디네이터가 두 단계 사이에 죽으면** 참여자는 커밋인지 롤백인지 모른 채(in-doubt) 락을 무한정 잡고 매달린다. 수동 개입 전까지 자원이 묶인다 — 2PC가 가용성·운영성에서 욕먹는 근본 이유.

</details>

<details>
<summary><b>Q2-c. 코디네이터 = ZooKeeper? (아님 ⚠️)</b></summary>

**2PC 코디네이터(TM)와 Kafka의 ZooKeeper는 완전히 다른 물건**이다. 흔한 혼동이라 못 박아둔다.

| | 2PC 코디네이터(TM) | ZooKeeper |
|---|---|---|
| 정체 | 분산 **트랜잭션** 지휘자 | 분산 **합의·메타데이터** 서비스 |
| 구체 예 | Atomikos, Narayana, 앱서버 JTA | 그냥 ZooKeeper |
| 하는 일 | prepare/commit 투표 주관 | 리더 선출·설정 저장·클러스터 멤버십 |
| Kafka와 관계 | 무관 | (구버전) 브로커 메타데이터·컨트롤러 선출 저장 |

ZooKeeper는 Kafka가 "브로커 몇 대 살았나, 누가 컨트롤러인가, 토픽 설정이 뭔가"를 관리하던 코디네이션 서비스지 **커밋을 조율하는 물건이 아니다.** 게다가 요즘 Kafka는 **KRaft 모드로 ZooKeeper를 아예 제거**했다. "ZooKeeper가 커밋을 조율한다"는 그림은 트랜잭션 코디네이터와 클러스터-메타데이터 코디네이터를 합쳐버린 오해다.

</details>

<details>
<summary><b>Q2-d. Kafka는 왜 XA를 지원 안 하나?</b></summary>

**Kafka는 `XAResource`를 구현하지 않는다** — 외부 TM의 prepare/commit 지휘를 받는 참여자가 될 설계를 안 했다.

- **철학 충돌**: XA prepare는 "발행할 메시지를 준비됨 상태로 붙잡고 외부 코디네이터의 최종 판정을 무한정 기다리며 블로킹"을 요구한다. 고처리량·저지연·파티션 병렬을 목표로 하는 로그 시스템과 정면충돌.
- **흔한 오해 — "Kafka도 트랜잭션 있잖아?"**: 맞다. `transactional.id`·EOS(exactly-once semantics)가 있다. 하지만 그건 **Kafka 내부에서만**(여러 파티션 + 컨슈머 오프셋) 원자적이다. **Kafka + 외부 Postgres를 하나의 글로벌 트랜잭션으로 묶는 건 못 한다** — XA 참여자가 아니니까.

결론: TM을 들여와도 Kafka가 XA 브랜치로 참여를 안 하므로 **Postgres + Kafka 2PC는 원천 불가능**하다. 이 불가능성 때문에 Kafka 생태계가 **outbox를 표준으로** 채택했다.

</details>

<details>
<summary><b>Q2-e. 그럼 TM은 별도 서버? Spring 서버나 커스텀 Java 서버?</b></summary>

방향은 맞지만 두 가지를 정확히: **① 이론상 그냥 소프트웨어지만 실무에선 직접 안 만들고, ② Spring도 TM을 *구현*하는 게 아니라 *위임*한다.**

- **TM은 보통 별도 서버가 아니라 "앱 안에 임베드된 라이브러리"**다. Atomikos·Narayana(Quarkus/JBoss)·Bitronix를 의존성으로 넣으면 당신 JVM이 코디네이터가 되고 복구 로그를 디스크에 쓴다. (드물게 앱서버 내장 TM, 더 드물게 독립 TM 서버.)
- **Spring의 역할은 facade**: `PlatformTransactionManager`는 추상화일 뿐이다. 단일 DB면 `DataSourceTransactionManager`/`JpaTransactionManager`인데 **이건 2PC를 못 하는 단일 리소스 전용**이다(우리 프로젝트가 쓰는 것). XA면 `JtaTransactionManager`인데 **이것도 2PC 엔진이 아니라 어댑터**고, 실제 투표는 뒤에 꽂은 Atomikos/Narayana가 한다. 즉 "Spring 서버가 TM"은 반쯤만 맞다 — Spring은 창구, 진짜 코디네이터는 그 밑의 JTA 구현체.
- **직접 커스텀 제작은 이론상 O, 실무상 X**: 내구성 트랜잭션 로그·크래시 복구·in-doubt 해소·heuristic 처리가 얽힌 지뢰밭이라 DB 엔진처럼 검증된 구현체를 쓴다.

**우리 프로젝트로 돌아오면**: JTA TM이 아예 없다. 단일 Postgres + 순수 `@Transactional`(단일 리소스)뿐이라 XA·2PC가 성립하지 않는다 — 그래서 dual-write를 outbox로 우회한다.

</details>

</blockquote>

</details>

<details>
<summary><b>Q3. 그냥 "커밋하고 나서 바로 발행"하면 안 되나? 코드 한 줄인데.</b></summary>

그게 바로 앞서 말한 **naive dual-write 안티패턴**이다. `commit()` 성공과 `publish()` 호출 사이에는 지울 수 없는 창이 있다 — 그 사이 프로세스가 죽으면 이벤트는 영영 사라진다. "재시도하면 되지"라고 하는 순간, **재시도할 대상(무엇을 아직 발행 못 했나)을 내구성 있게 기록**해야 하는데, 그 기록이 바로 outbox 테이블이다. 즉 커밋-후-발행을 안전하게 만들려고 하면 **결국 outbox를 재발명**하게 된다. 반대로 "발행-후-커밋"은 롤백 시 유령 이벤트를 낳아 더 나쁘다.

</details>

<details>
<summary><b>Q4. CDC(Debezium)가 요즘 더 낫다던데? outbox는 폴링이라 지연·DB부하가 있잖아.</b></summary>

맞는 지적이고, **CDC는 이 outbox의 미래 진화 경로**다. 다만 *지금* 도입하지 않는 이유가 분명하다:

- **CDC의 값**: WAL/binlog를 tailing해 폴링 없이 저지연으로 변경을 흘린다. 앱이 발행 코드를 안 짜도 된다.
- **지금 대가가 값보다 큼**: Debezium + Kafka Connect + Kafka 브로커를 세워야 한다. 단일 DB 모듈러 모놀리스에 이 인프라는 과하다(ADR-004: 측정이 정당화할 때만 인프라 추가). 현재 규모에선 in-process 디스패치로 충분하다.
- **경계 함정**: 도메인 테이블을 직접 CDC하면 내부 컬럼이 BC 밖으로 새어 경계를 깬다. 그래서 제대로 하려면 **"CDC of the outbox table"**(도메인 테이블이 아니라 outbox만 tailing) 형태여야 하는데 — **그러려면 어차피 지금의 outbox 테이블이 필요하다.**
- **진화가 매끄럽다**: 우리 outbox 테이블은 이미 CDC-ready다. 스케일이 폴링의 knee(로드맵 §7)를 정당화하면, 릴레이를 `@Scheduled` 폴링 → Debezium tailing으로 **같은 테이블 위에서** 교체하면 된다. 도메인 코드 변경 거의 없음(로드맵: "outbox 릴레이 → Kafka").

즉 outbox는 CDC의 대안이 아니라 **CDC로 가는 발판**이다. 지금은 폴링, 필요가 측정되면 tailing.

<blockquote>

🔬 <b>더 깊이 — CDC·WAL·tailing의 실체</b> (아래 중첩 토글)

<details>
<summary><b>Q4-a. CDC가 정확히 뭔가?</b></summary>

**CDC = Change Data Capture(변경 데이터 캡처).** DB에서 일어난 행 단위 변경(INSERT/UPDATE/DELETE)을 붙잡아 이벤트 스트림으로 흘려보내는 기법이다. 발상의 전환은 이것:

> "앱이 명시적으로 이벤트를 발행"하는 대신, **DB에 일어난 변경 자체를 이벤트의 원천으로 삼는다.**

앱은 평소처럼 자기 트랜잭션에서 INSERT/UPDATE만 하고, 그 변경이 커밋되면 외부 도구(Debezium)가 감지해 Kafka로 이벤트를 쏜다. 앱은 발행을 "지시"하지 않는다.

</details>

<details>
<summary><b>Q4-b. WAL / binlog가 뭔가?</b></summary>

모든 ACID 관계형 DB는 내구성(Durability)과 크래시 복구를 위해, 변경을 데이터 파일에 반영하기 **전에** 순차 append-only 로그에 먼저 기록한다. Postgres는 **WAL**(Write-Ahead Log), MySQL은 **binlog**.

원리(Write-Ahead Logging): 로그를 디스크에 먼저 flush해야 커밋으로 인정한다. 크래시 시 이 로그를 재생해 복구하고, 복제(replication)도 이 로그를 리플리카가 재생해 따라온다. 즉 **WAL은 커밋된 모든 변경의 순서 있는 권위 기록**이다.

핵심: WAL은 CDC를 위해 만든 게 아니라 **DB가 살아남으려고 원래부터 항상 쓰는** 로그다. CDC는 여기에 무임승차한다.

</details>

<details>
<summary><b>Q4-c. 왜 tailing이 폴링보다 저지연·저부하인가?</b></summary>

- **폴링(현재 릴레이)**: 1초마다 `SELECT ... WHERE published_at IS NULL`. 새 게 없어도 매번 쿼리(헛일) + 있어도 최대 1초 지연.
- **tailing(CDC)**: Debezium이 또 하나의 리플리카처럼 DB 복제 스트림에 붙어 WAL을 `tail -f` 하듯 따라간다(Postgres는 logical decoding + 복제 슬롯). DB가 어차피 쓰는 WAL을 따라 읽으니 커밋 순간 거의 실시간 push, 반복 SELECT 부하 없음, 로그가 순차라 순서도 보장.

그래서 "폴링(반복 조회) 없이 저지연"이다.

</details>

<details>
<summary><b>Q4-d. ⚠️ 방향 교정 — CDC는 DB 쓰기를 게이팅하지 않는다</b></summary>

"발행됐는지 확인해서 한다" 또는 "Debezium 보고 insert할지 정한다"는 **방향이 거꾸로**다. CDC는 단방향·사후(downstream)다:

```
① 앱이 DB에 INSERT/UPDATE → 커밋 (평범한 로컬 ACID)
② 그 커밋이 WAL에 기록됨
③ Debezium이 WAL을 읽음 (이미 커밋된 것만)
④ Kafka로 이벤트 발행
⑤ 소비자가 반응
```

Debezium은 **이미 커밋된 변경을 사후에 읽어 흘리는 독자(reader)**다. insert 여부는 앱이 이미 결정·커밋했고, Debezium은 결과를 관찰만 한다. 롤백된 변경은 커밋 스트림에 안 나타나 발행되지 않는다.

</details>

<details>
<summary><b>Q4-e. 그럼 CDC는 무조건 ACID한가?</b></summary>

부분적으로만이다.
- **DB 커밋 자체는 ACID** ✓ (로컬 트랜잭션).
- 하지만 **"DB 커밋"과 "Kafka 도착"은 하나의 원자 트랜잭션이 아니다.** Debezium이 크래시 후 마지막 오프셋부터 재시작하면 같은 이벤트를 재발행(at-least-once)할 수 있다.

결론: CDC도 **분산 ACID를 주지 않는다.** "커밋된 변경의 확실한 캡처 + at-least-once 전달"을 줄 뿐이라, **소비자 멱등(우리 `uq` + ON CONFLICT)은 여전히 필요**하다. → CDC와 outbox는 같은 dual-write 문제를 같은 effect-모델(at-least-once + 멱등 소비)로 푸는, 메커니즘만 다른 형제다.

</details>

<details>
<summary><b>Q4-f. 왜 도메인 테이블 CDC는 경계를 깨고, "outbox 테이블 CDC"여야 하나? (왜 이미 CDC-ready?)</b></summary>

**도메인 테이블 직접 CDC의 문제**: Debezium이 `shipment` 테이블을 tailing하면 그 **내부 컬럼 전부**(status 캐시, source_event_id 등 물리 스키마)가 이벤트 payload 모양이 된다.
- **결합**: 소비자가 shipping의 물리 스키마에 의존 → 컬럼 리팩터가 소비자를 깬다. AGENTS.md §1(BC 내부 테이블 직접 참조 금지) 위반.
- **의미**: raw 행 변경은 도메인 이벤트가 아니다. "how it's stored"가 새어 나가고 "what happened"를 못 준다.

**해법 = Outbox + CDC**: 도메인 tx에서 큐레이션된 이벤트(event_type, 버전 payload)를 `outbox`에 적재하고, Debezium은 **`outbox` 테이블만** tailing한다(Debezium엔 이걸 위한 *Outbox Event Router* SMT까지 있다). 계약이 내가 설계한 payload(published 계약)지 내부 스키마가 아니다.

→ 즉 **CDC를 써도 outbox 테이블은 필요**하다. CDC는 폴링 릴레이를 대체하지 outbox를 없애지 않는다. 우리는 이미 전용 `outbox`에 `aggregate_type`·`aggregate_id`·`event_type`·`payload(JSONB)`로 큐레이션 이벤트를 도메인 tx에서 쓰고 있어 **정확히 Debezium outbox 라우터가 기대하는 모양** — 이게 "이미 CDC-ready"의 뜻이다. 전환 시 Debezium을 outbox에 붙이고 폴링만 끄면 되고, 도메인 코드·이벤트 계약·소비자 멱등은 그대로다.

</details>

</blockquote>

</details>

<details>
<summary><b>Q5. 왜 이벤트인가? shipping이 settlement를 그냥 직접 호출하면 안 되나?</b></summary>

동기 직접 호출은 **BC 자율성을 깬다**(황금률 §1). 두 가지가 결합된다:

- **가용성 결합**: settlement가 느리거나 죽으면 배송 전이가 막힌다. 배송 완료는 정산 로직(수수료 정책·주기 마감 — 진화 중이고 무거울 수 있음)의 성공 여부와 무관하게 확정돼야 한다.
- **배포·소유권 결합**: 미래에 정산을 별 프로세스로 추출할 때(로드맵), 직접 호출은 그 경계를 다시 뜯어야 한다. 이벤트는 그 경계를 지금 코드에 박아둔다.

outbox 이벤트는 "shipping은 자기 진실을 커밋하고, settlement는 자기 페이스로 소비한다"는 DDD 경계를 **운영 가능한 형태**로 만든 것이다. M3-a(결제확정→배송)도 정확히 같은 이유로 이벤트였고, 이 슬라이스는 그 선례와 일관된다.

<blockquote>

🔬 <b>더 깊이 — 이벤트 기반이면 Saga인가?</b> (아래 중첩 토글)

<details>
<summary><b>Q5-a. Saga 패턴이 뭔가?</b></summary>

**여러 서비스(BC)에 걸친 하나의 비즈니스 트랜잭션을, 분산 원자 커밋(2PC) 없이 정합성 있게 끝내는 패턴**이다. 하나의 큰 분산 트랜잭션 대신 **각 서비스의 로컬 트랜잭션 여러 개를 순서대로** 잇고, 각 단계가 다음을 트리거하는 이벤트를 낸다. 중간 실패 시 이미 커밋된 앞 단계를 **보상 트랜잭션(compensating transaction)**으로 되돌린다.

결정적 포인트: **rollback이 아니라 compensation.** 다른 서비스에서 이미 커밋된 tx는 되돌릴 수 없으니, 반대 효과를 내는 새 tx로 의미적으로 상쇄한다.

```
① 주문 생성 → ② 재고 예약 → ③ 결제 승인 → ④ 주문 확정
                             ↓ 결제 실패
        보상: 재고 예약 해제 ← 주문 취소   (커밋된 ①②를 새 tx로 되돌림)
```

두 방식: **Choreography**(중앙 조율 없음, 각자 이벤트 듣고 반응 — 우리 스타일) vs **Orchestration**(중앙 오케스트레이터가 지휘·보상).

우리 정산이 append-only 부호 원장(환불 = 음수 라인 추가, ADR-010)인 게 이 **보상 원리를 돈에 적용**한 것이다 — "취소"를 원본 삭제가 아니라 반대부호 새 줄로 표현.

</details>

<details>
<summary><b>Q5-b. 왜 outbox와 saga를 묶어 부르나? (층이 다르다)</b></summary>

| | Saga | Outbox |
|---|---|---|
| 정체 | 워크플로·정합성 모델 (what) | 신뢰 메시징 배관 (how) |
| 푸는 문제 | 여러 서비스 걸친 트랜잭션을 보상으로 정합 | 로컬 커밋 + 이벤트 발행의 원자성(dual-write) |

Saga의 각 단계는 "내 로컬 tx 커밋 + 다음 단계 트리거 이벤트 발행"을 함께 해야 하는데, 이게 곧 dual-write 문제다. 커밋됐는데 이벤트가 유실되면 saga가 멈추거나 어긋난다. 그래서 **choreography saga의 각 스텝을 신뢰성 있게 만드는 표준 배관이 outbox**다. 같은 것이 아니라 **보완 레이어**라 한 문장에 같이 등장한다.

</details>

<details>
<summary><b>Q5-c. 그럼 outbox면 무조건 saga로 가야 하나? — 아니오</b></summary>

outbox는 그냥 **"DB 트랜잭션에서 신뢰성 있게 이벤트를 내보내는 배관"**일 뿐이고, saga는 그 위에서 도는 여러 용도 중 하나다.

outbox를 쓰지만 saga가 **아닌** 경우가 더 흔하다: 이벤트가 read model 갱신·캐시 무효화·검색 색인·분석·독립 downstream을 트리거만 하고 **되돌릴(보상할) 게 없는** 경우. Saga는 **"보상이 필요한, 여러 서비스 걸친 다중 스텝 비즈니스 트랜잭션"**일 때만 붙는 이름이다.

**우리 프로젝트:**
- **shipping → settlement(이 PR)**: 현재는 되돌아가는 보상 흐름이 없는 **단방향 알림(choreography-lite)**. 엄밀히는 아직 saga라기보다 event-carried 알림.
- **전체 돈 흐름(주문→결제→배송→정산→환불)**: saga 성격 — 특히 **환불(M5)이 곧 보상 트랜잭션**이고 다중 스텝 분산 프로세스다. 단 중앙 오케스트레이터 없는 choreography.
- **outbox**: saga든 단순 알림이든 밑에서 똑같이 받치는 공통 배관.

**한 줄**: Saga는 워크플로 패턴, Outbox는 메시징 배관. Saga는 신뢰 메시징이 필요해 흔히 outbox 위에 얹히지만, **outbox는 보상 없는 단순 전파에도 쓰이므로 outbox ⇏ saga**다.

</details>

</blockquote>

</details>

<details>
<summary><b>Q6. at-least-once면 중복이 오잖아. 이중 정산(돈 두 번 지급)은 어떻게 막나?</b></summary>

**정확히-한-번 전달(exactly-once delivery)은 분산계에서 사실상 불가능**하므로, 목표를 바꾼다 — **정확히-한-번 효과(effect)를 멱등 소비자로 달성**한다.

- 발행 측(이 PR): outbox 행은 `uq_outbox_event(aggregate_type, aggregate_id, event_type)`로 배송당 1건. 게다가 DELIVERED는 종료 상태 + 핫로우 락이라 애초에 1회만 append(이 PR의 동시성 테스트가 실증: 16스레드 → 이벤트 정확히 1건).
- 소비 측(M4-b): settlement는 `uq_settline_event(source_event_id, entry_type, seller_id)` + `ON CONFLICT DO NOTHING`으로 같은 이벤트 재수신을 no-op으로 흡수한다(ADR-010 복합 멱등키 — fan-out은 허용, 진짜 중복만 거부). 배송 생성(M3-a)도 같은 `ON CONFLICT` 방식이었다.

그래서 릴레이가 크래시로 두 번 전달해도 정산 라인은 한 번만 생긴다. 중복은 정상 흐름의 일부로 삼켜진다.

</details>

<details>
<summary><b>Q7. 발행을 왜 굳이 전이와 같은 트랜잭션에? 발행 실패로 전이까지 롤백되는 게 맞나?</b></summary>

의도된 설계다. 대안(전이 커밋 → 별도로 append)은 Q0의 "커밋 후 발행 실패 → 이벤트 유실 → 미정산"을 그대로 부른다. **같은 트랜잭션**이면 append 실패 시 전이도 함께 롤백된다 → 배송은 "아직 완료 아님"으로 남고, 클라이언트 재시도가 둘을 다시 원자적으로 시도한다. **돈에서는 가용성보다 정합성**이라, "완료로 보이지만 정산 신호 없음"보다 "완료가 아직 안 됨(재시도 가능)"이 옳다. 여기서 append 23505(중복)는 종료 상태 특성상 발생하지 않지만, 만약 구조가 깨져 발생하면 fail-loud로 전이를 막는다(`OutboxAppender`가 plain INSERT인 이유).

</details>

<details>
<summary><b>Q8. 폴링 릴레이의 한계(지연·순서·테이블 비대)는? 스케일하면 어쩌나?</b></summary>

정직하게 한계를 인정하고 진화 경로를 둔다(로드맵과 정합):

- **지연**: 발행이 폴링 주기(현재 1s)만큼 늦다. 정산은 사용자 대면 실시간이 아니라 허용된다. 저지연이 필요해지면 CDC로 전환(Q4).
- **DB 부하**: `published_at IS NULL` 스캔. 부분 인덱스로 완화하고, 처리량 knee는 부하 테스트로 실측한다(로드맵 §7). knee가 분리를 정당화하면 Kafka로.
- **순서**: outbox는 `id` 단조 증가 순 처리. 한 배송의 이벤트는 핫로우 락으로 이미 직렬화돼 per-aggregate 순서가 보장된다. 정산은 배송 간 독립이라 전역 순서가 필요 없다. 필요해지면 `aggregate_id`로 파티셔닝(Kafka key).
- **테이블 비대**: 발행된 행은 보존기간 후 아카이브/삭제하는 reaper가 필요하다 — 이 슬라이스 범위 밖(후속). 지금은 규모가 작아 미도입.

핵심은 "지금 단순하게, 측정이 정당화하면 진화"다(ADR-004). 폴링은 임시방편이 아니라 **현재 규모에 맞는 정답**이고, 테이블은 그 진화를 막지 않는 자산이다.

</details>

<details>
<summary><b>Q9. 한 줄 요약으로, 왜 이 아키텍처에서 outbox가 최선인가?</b></summary>

**dual-write 정합성을 분산 원자성(2PC의 비용) 없이, 새 인프라(브로커·CDC의 비용) 없이, 단일 Postgres의 로컬 트랜잭션만으로 얻으면서 — 동시에 BC 경계를 지키고, 미래의 Kafka/CDC 전환 발판까지 남기기 때문.** 현재 제약(단일 DB·모놀리스·브로커 없음·돈 우선)에 정확히 들어맞는 유일한 지점이 outbox다. 2PC는 가용성을, 직접 호출은 경계를, 커밋-후-발행은 정합성을, CDC는 지금의 단순함을 판다. outbox만 넷을 다 지킨다.

</details>
