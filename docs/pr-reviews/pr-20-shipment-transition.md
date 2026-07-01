# PR #20 리뷰 정리 — 배송 상태 전이 (S4, M3-b)

> 대상 PR: `feat(shipping): 배송 상태 전이 — 화이트리스트 상태머신 + 핫로우 직렬화 (S4, M3-b)`
> 리뷰어: Gemini Code Assist, CodeRabbit (ASSERTIVE)
> 이 문서는 봇 리뷰를 AGENTS.md 규칙에 비춰 **적대적으로 재판정**한 결과입니다(§4). 봇 지적을 그대로 수용하지 않고, 타당한 것·과잉인 것·문서로 대체할 것을 구분합니다.

관련 파일:
- `shipping/application/ShipmentService.java`
- `shipping/web/ShipmentController.java`
- `shipping/web/ShipmentRequests.java`
- `shipping/test/…/ShipmentTransitionConcurrencyIT.java`
- `shipping/test/…/ShipmentTransitionIT.java`
- 스키마: `db/migration/V4__shipping.sql`

---

## 처리 요약

| # | 이슈 | 판정 | 노력 | 상태 |
|---|------|------|------|------|
| 1 | 응답이 "방금 기록한 전이"가 아닐 수 있다 | ✅ 수정 | 소 | **DONE** — recordTransition이 View 반환 |
| 2 | GET 조회가 서로 다른 커밋을 섞어 볼 수 있다(torn read) | ✅ 수정 | 소 | **DONE** — (a) REPEATABLE READ |
| 3 | 전이에 멱등키(source_event_id)가 없다 | 📌 defer | — | **DONE** — 코드 주석 + ADR-015 근거 |
| 4 | 불법 전이가 DB 우회 삽입으로 가능 | 📌 defer(b) | — | **DONE** — ADR-015에 결정 박제 |
| 5 | 동시성 테스트가 퇴행을 못 잡는다 | ✅ 수정 | 소 | **DONE** — DIVE를 isZero 단언 |
| 6 | 불법 전이 IT가 409만 보고 상태 불변은 안 본다 | ✅ 수정 | 소 | **DONE** — assertUnchanged |
| 7 | ShipmentRequests Javadoc → 라인주석 | ✅ 완료 | 극소 | **DONE** |

> #4는 (a) DB 트리거 대신 **(b) ADR defer**로 결정. `decision-log.md` ADR-015 "리뷰 반영(PR #20) ⑦"에 "write 경로가 하나뿐 → 앱 강제, 두 번째 경로 생기면 트리거 추가" 근거를 박제했다.

---

## 1. 응답이 "방금 기록한 전이"가 아닐 수 있다 ⭐

**어디서:** `ShipmentController.recordEvent`

```java
@PostMapping("/{id}/events")
public ShipmentView recordEvent(@PathVariable long id, @Valid @RequestBody ShipmentRequests.RecordEvent req) {
    shipments.recordTransition(id, req.status(), req.occurredAt());  // tx1: 잠금·전이·커밋
    return shipments.getShipment(id);                                // tx2: 새 트랜잭션에서 다시 조회
}
```

**어떻게 도는가:** `ShipmentService`에 클래스 레벨 `@Transactional`이 있어 `recordTransition`은 그 자체로 트랜잭션 1개다. 이 안에서 `SELECT ... FOR UPDATE`로 shipment 행을 잠그고 전이를 기록한 뒤 **커밋한다**. 그다음 컨트롤러가 `getShipment`를 **또 다른 트랜잭션**으로 호출해 응답을 만든다.

**무엇이 문제인가:** tx1 커밋과 tx2 조회 사이에 **같은 배송에 다른 전이가 끼어들어 커밋**되면, 응답이 내가 만든 `PICKED_UP`이 아니라 그다음 `IN_TRANSIT`을 반환한다. Read-Your-Writes(내가 쓴 걸 내가 읽는다)가 깨진다. 트랜잭션도 2번 연다. PR 설계 의도("전이 응답 = 잠금 안에서 확정한 스냅샷")와도 어긋난다.

**판정:** 타당. 수정.

**어떻게 고치나:** `recordTransition`이 `void` 대신 `ShipmentView`를 반환하도록 올린다. **잠금을 쥔 같은 트랜잭션 안에서** 이력·현재 상태를 조립해 반환한다. 다른 전이는 `FOR UPDATE`에 막혀 끼어들 수 없으므로 응답은 정확히 내가 쓴 상태다.

```java
public ShipmentView recordTransition(long shipmentId, ShipmentStatus target, OffsetDateTime occurredAt) {
    // ... 잠금 → 검증 → most_recent 내리기 → append → status 갱신 (기존과 동일) ...
    return buildView(shipmentId);   // getShipment의 조회부를 private로 분리해 같은 tx에서 재사용
}
```

컨트롤러는 `return shipments.recordTransition(...);` 한 줄.

---

## 2. GET 조회가 서로 다른 커밋을 섞어 볼 수 있다 (torn read)

**어디서:** `ShipmentService.getShipment` — head(`shipment.status`)와 history(`shipment_event`)를 **두 번의 SELECT**로 읽는다.

**무엇이 문제인가:** PG 기본 격리수준은 **READ COMMITTED**라, 한 트랜잭션 안에서도 각 statement는 "그 시점의 최신 커밋"을 본다. 두 SELECT 사이에 전이가 커밋되면 `status=READY`인데 history 마지막은 `PICKED_UP`인 자기모순 응답이 나올 수 있다.

**판정:** 기술적으로 사실이나 우선순위 낮음(읽기 전용). #1을 고치면 전이 응답 경로는 이미 안전하고, 독립 GET에만 이 창이 남는다.

**수정 방향 (택1):**

### (a) `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)` — 1줄

- **개념:** 격리수준은 DB 전역 설정이 아니라 **트랜잭션 하나하나가 스스로 요청하는 값**이다. Spring의 `isolation` 속성은 트랜잭션 시작 시 JDBC `Connection.setTransactionIsolation(...)`를 호출해 **그 트랜잭션만** 격리수준을 올린다. DB 기본값(READ COMMITTED)과 다른 트랜잭션은 영향 없다.
- REPEATABLE READ가 되면 PG는 트랜잭션 **첫 쿼리 시점에 스냅샷을 한 번** 뜨고, 이후 모든 SELECT가 그 동일 스냅샷을 본다 → 두 SELECT가 어긋나지 않는다.
- 가장 싸다.

### (b) status를 `shipment_event.most_recent`에서 파생 — 더 정합적

- **현재:** 상태가 **두 군데**에 있다 — `shipment.status`(읽기 캐시, V4 line 22 주석: "권위는 shipment_event.most_recent")와 `shipment_event.most_recent` 행의 `to_status`(진짜 권위). 서로 다른 테이블이라 어긋날 수 있다.
- **바꾸면:** status를 `shipment.status`에서 읽지 않고 **history의 most_recent 행 to_status에서 파생**한다. status와 history가 같은 한 번의 event 조회에서 나와 **애초에 어긋날 수 없다.**
- 이 프로젝트 철학(정산 잔액을 컬럼 UPDATE 대신 `SUM`으로 도출 — "캐시하지 말고 원장에서 도출")과 같은 결. 대신 읽기 쿼리를 다시 짜고 `shipment.status` 캐시 컬럼의 존재 이유를 재검토해야 한다.

**권장:** #1을 고치면 남는 건 독립 GET뿐이라, 비용 대비 (a)로 충분. 철학적 정합성을 우선한다면 (b).

---

## 3. 전이에 멱등키(source_event_id)가 없다

**리뷰 주장:** `recordTransition`엔 멱등키가 없어 같은 외부 이벤트 재전달 시 첫 처리 뒤 자가 전이 409가 된다. at-least-once 소비자가 "이미 처리됨"과 진짜 에러를 구분 못 한다.

**판정: 지금은 과잉. 문서화 후 defer.**

- 이 엔드포인트는 아직 **이벤트 소비자가 아니라 동기 REST API**다. at-least-once의 원천인 **택배사 추적 콜백은 이 PR이 명시적으로 scope-out** 했다.
- 생성 경로(`createForPaymentConfirmed`)엔 이미 `(source_event_id, seller_id)` 멱등이 있다(V4 line 25).
- 콜백이 붙는 슬라이스에서 함께 넣는 게 AGENTS.md 스코프 규율(로드맵 밖 선구현 금지)에 맞다.

**처리:** 코드 대신 `decision-log.md`에 "전이 멱등은 택배사 콜백 슬라이스로 defer" 근거를 남기고 코멘트 resolve.

---

## 4. 불법 전이가 DB 우회 삽입으로 여전히 가능 🤔 결정 필요

**리뷰 주장:** 화이트리스트 검사가 앱 계층에만 있다. 수동 SQL이나 다른 write 경로가 `READY→DELIVERED`를 그대로 넣을 수 있고, most_recent가 오염되면 이후 읽기·전이도 오염된다.

**판정: 이 코드베이스에서 가장 뼈아픈 지적.** AGENTS.md §1.2 황금률이 정확히 이것 — *"불변식은 if문이 아니라 DB 제약으로 박제, 우회 경로를 새로 짜도 DB가 막아야 한다."* V4 line 51–52도 "전이 합법성은 앱 화이트리스트의 몫"이라고 **의도적으로** 남긴 자리다.

**수정 방향 (택1):**

### (a) Flyway 새 마이그레이션으로 DB 강제 (황금률 준수)

두 층이 있다:
1. **체인 무결성** — 새 이벤트의 `from_status`가 직전 most_recent 행의 `to_status`와 같아야 한다. 다른 행을 봐야 하므로 CHECK로 안 되고 **BEFORE INSERT 트리거**가 필요하다(현재 most_recent 조회 → 불일치면 `RAISE EXCEPTION`).
2. **전이쌍 화이트리스트** — 참조 테이블 `shipment_transition(from_status, to_status)`을 만들고 트리거/FK로 합법 쌍만 허용.

이러면 수동 SQL로도 `READY→DELIVERED`를 못 넣는다. 앱 enum 체크는 "빠른 실패 + 예쁜 409"로 남는다.

- **트레이드오프:** 화이트리스트가 enum(Java)과 DB 두 곳에 살아 동기화 부담. 값싼 체인 무결성(from = 직전 to)만 트리거로 넣고 전체 쌍은 앱에 두는 절충도 가능.

### (b) ADR로 defer 근거 명시

`decision-log.md`에 결정을 박제한다: *"shipment_event write 경로가 ShipmentService 단 하나이므로 전이 합법성은 앱 계층에서만 강제한다. 두 번째 write 경로가 생기면 DB 트리거를 추가한다. 리스크 수용, M-x 재검토."* 갭을 몰래 두는 게 아니라 의식적·추적 가능하게 두는 정당한 선택.

**미결정:** §1.2 무게상 (a)에 기운다. 사용자 결정 대기.

---

## 5. 동시성 테스트가 퇴행을 못 잡는다 ⭐

**어디서:** `ShipmentTransitionConcurrencyIT` line 71–72

```java
} catch (IllegalStateException e) {
    rejected.incrementAndGet(); // 갱신된 상태(PICKED_UP)에서 자가 전이 → 기대된 거부
} catch (org.springframework.dao.DataIntegrityViolationException e) {
    rejected.incrementAndGet(); // 최후 보루(most_recent 부분 UNIQUE)도 거부로 인정  ← 문제
}
```

**올바른 `FOR UPDATE` 직렬화가 하는 일:** 16스레드가 동시에 `READY→PICKED_UP` 시도 →
1. 딱 1개만 shipment 행 잠금 획득, 15개는 대기
2. 승자: most_recent=`READY` → 합법 확인 → 전이 기록 → **커밋 → 잠금 해제**
3. 패자 15개가 한 명씩 잠금 획득 → most_recent를 **다시 읽으면 `PICKED_UP`**(잠금 대기 = 앞 트랜잭션 커밋 대기, READ COMMITTED) → `PICKED_UP→PICKED_UP` 자가 전이 → **`IllegalStateException`(409)**

→ 정상이면 패자는 **전부 `IllegalStateException`**.

**DIVE는 언제 나나:** 잠금이 **깨지면**(FOR UPDATE 제거 등) 두 스레드가 동시에 `READY`를 읽고 둘 다 합법 판정 후 둘 다 `most_recent=TRUE` 행을 INSERT → `uq_shipevent_most_recent` 부분 UNIQUE가 두 번째를 거부 → **DataIntegrityViolationException**. 이게 "최후 보루".

**무엇이 문제인가:** 잠금이 퇴행하면 패자가 `IllegalStateException` 대신 **DIVE**로 떨어진다. 그런데 지금 테스트는 **DIVE도 정상 거부로 카운트**해서, 잠금이 살았든 죽었든 `success=1, rejected=15`가 똑같이 성립 → **초록 유지** → 잠금이 깨진 걸 아무도 모른다. AGENTS.md가 금지하는 "그럴듯하지만 아무것도 검증 안 하는" 테스트.

**어떻게 고치나:** DIVE를 **거부가 아니라 실패로** 취급한다. 별도 카운터에 담고 마지막에 0을 단언한다.

```java
AtomicInteger dbFallback = new AtomicInteger();
// ...
} catch (org.springframework.dao.DataIntegrityViolationException e) {
    dbFallback.incrementAndGet(); // 잠금이 깨져 UNIQUE 최후보루로 떨어짐 = 회귀
}
// ...
assertThat(success.get()).isEqualTo(1);
assertThat(rejected.get()).isEqualTo(threads - 1);
assertThat(dbFallback.get()).as("FOR UPDATE 직렬화가 살아있으면 UNIQUE 최후보루로 떨어질 일이 없다").isZero();
```

- 잠금 정상: `success=1, rejected=15, dbFallback=0` → 초록
- 잠금 퇴행: `dbFallback>0` → **빨강** → "직렬화가 깨졌다" 즉시 신호

(catch를 그냥 지우면 스레드 안 예외가 pool에 삼켜져 신호가 애매해진다. 명시적 카운터 + zero 단언이 제일 깨끗하다.)

---

## 6. 불법 전이 IT가 409만 보고 "상태 불변"은 안 본다 ⭐

**어디서:** `ShipmentTransitionIT`의 `skipRejected`(단계 건너뛰기), `backwardRejected`(역전이), `selfRejected`(자가), `fromTerminalRejected`(종료 상태에서 전이) — 응답이 **409인지만** 단언한다.

**무엇이 문제인가:** 거부된 전이는 **완전한 no-op**이어야 한다 — 새 `shipment_event` 행 0개, most_recent 그대로, `shipment.status` 그대로(append-only + 단일 head 불변식). 409를 주면서도 부분 쓰기가 새어나가는 회귀(검증 순서 변경, 롤백 누락)를 지금 테스트는 못 잡는다.

> **현재 코드는 안전하다.** `recordTransition`은 검증을 어떤 쓰기보다 먼저 throw하고, `IllegalStateException`(RuntimeException)이라 `@Transactional`이 자동 롤백한다. 이 테스트 추가는 버그 수정이 아니라 **미래 회귀 방어막**이다(AGENTS.md "반례를 테스트로 박제").

**어떻게 고치나:** 각 409 단언 **직후** DB 확인:
- `shipment.status` == 원래 상태(예: 여전히 `READY`)
- `count(shipment_event)` == 거부 전 개수(안 늘었다)
- `count(*) WHERE most_recent` == 1(head 하나, 뒤집힘·중복 없음)

"에러를 던진다"를 "에러를 던지고 **아무 흔적도 안 남긴다**"로 강화.

---

## 7. ShipmentRequests Javadoc → 라인주석 ✅ 완료

**어디서:** `ShipmentRequests.java` line 12. `occurredAt` 기본값 설명은 구현 메모라 §6.4상 `/** */`가 아니라 `//`여야 한다.

**수정 완료:**

```java
// occurredAt은 택배사 사건 시각 — 생략하면 서버가 현재로 본다.
public record RecordEvent(@NotNull ShipmentStatus status, OffsetDateTime occurredAt) {
```

