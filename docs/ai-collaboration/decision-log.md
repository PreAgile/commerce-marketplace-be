# 결정 기록 (ADR) — 핵심 설계 결정 + AI 활용·검증

> 각 결정에 대해 **맥락 / 결정 / AI를 어떻게 썼나 / 어떻게 검증했나**를 기록합니다.
> "AI가 제안했으니 맞다"가 아니라 "AI 제안을 무엇으로 검증했나"가 핵심입니다. 상세 설계 근거는 [`../design/system-design.md`](../design/system-design.md)를 참조하세요.

---

## ADR-001. 정산을 "부호 있는 append-only 원장"으로 (잔액 컬럼 UPDATE ❌)

- **맥락**: 셀러 잔액을 단일 컬럼으로 두면 동시 갱신·이력 추적·순서 역전에 취약하다.
- **결정**: `settlement_line`을 부호(±) amount의 append-only 원장으로, 잔액은 `SUM(amount_minor)`로 도출.
- **AI 활용**: 토스·우아한형제들 정산 사례를 함께 조사해 모델 도출.
- **검증**: property test — "REFUND가 SALE보다 먼저 적재돼도 최종 SUM 동일"(덧셈 교환법칙)을 랜덤 시나리오로. 이 선택이 **순서 역전 문제를 구조적으로 녹인다**는 걸 테스트로 확인.

## ADR-002. dual-write는 outbox로 (2PC ❌)

- **맥락**: DB 커밋과 메시지 발행을 한 트랜잭션으로 못 묶는다. Kafka는 2PC에 못 낀다.
- **결정**: 도메인 변경과 `outbox` INSERT를 한 TX로 원자화, 릴레이가 사후 발행. at-least-once + 멱등키(`source_event_id`)로 중복 흡수.
- **AI 활용**: 분산 원자성 해법 5종(2PC/outbox/CDC/listen-to-yourself/event sourcing)을 AI와 비교.
- **검증**: 결정론 시뮬 — "커밋 직후/발행 전 크래시 → 재기동 → 재처리"에도 이벤트 유실·중복발행 0.

## ADR-003. Mock은 외부 경계만, DB·도메인은 진짜

- **맥락**: 불변식의 절반이 DB 제약에 있어, DB를 mock하면 거짓 안심이 생긴다.
- **결정**: DB·도메인 = Testcontainers 실 Postgres. PG·택배·시계(Clock) = mock. "상태 검증, `verify` 금지".
- **AI 활용**: AI에게 mock 경계를 명시적으로 지시해 mock 남발을 억제.
- **검증**: 양수 REFUND 같은 위반을 일부러 시도 → 가짜 repo는 통과시키지만 실 DB는 CHECK로 거부함을 테스트로 대조.

## ADR-004. 마이크로서비스는 "측정이 정당화할 때만" (조기 분리 ❌)

- **맥락**: 처음부터 쪼개면 네트워크·분산TX·운영비를 근거 없이 떠안는다.
- **결정**: modular monolith로 시작, 로컬에서 한계까지 밀어 병목을 데이터로 발견한 뒤 첫 서비스 추출.
- **AI 활용**: 병목→분리 매핑 표를 AI와 작성([`../design/implementation-spec.md`](../design/implementation-spec.md) §7).
- **검증**: 부하 테스트 실측(§7-3b) — knee와 원인(핫로우 락 등)을 그래프로. *예정*.

## ADR-005. 락 모드 비대칭 — AI가 틀렸고, 적대적 검증으로 잡은 사례 ★

- **맥락**: 정산 마감과 라인 유입의 동시성 제어.
- **최초(틀린) 결정**: "cycle 행을 `FOR SHARE`로 직렬화" — **오류**. 공유 락끼리는 상호배제가 안 된다.
- **정정**: 유입=`FOR SHARE` / 마감=`FOR UPDATE` **비대칭**(SHARE↔UPDATE 비호환) + 트리거 fail-closed로 불변식 강제 + `ORDER BY cycle_id` 락 순서로 데드락 예방.
- **AI 활용·검증**: AI를 **적대적 면접관**으로 세워 자기 설계를 공격하게 한 결과 이 오류를 발견([`verification-loop.md`](./verification-loop.md)). → "AI가 만든 걸 AI로 검증하고 사람이 판단"의 대표 사례.

## ADR-006. 대사 = "drift≠0 알람"이 아니라 상태 모델 — 역시 적대적 검증으로 정정

- **맥락**: PG 입금 대사.
- **최초(틀린) 결정**: "drift≠0 = break 알람" — **오류**. PG 입금 시차로 정상 drift가 상존해 alert fatigue.
- **정정**: recon 상태 모델(EXPECTED/IN_TRANSIT/SETTLED/BREAK), 알람은 BREAK≠0만, 닫힌 cutoff + REPEATABLE READ 스냅샷, grace+hysteresis로 false-positive 흡수.
- **검증**: "in-transit은 알람 안 뜨고 grace 초과만 break" 테스트. *예정*.

## ADR-007. AI 리뷰의 stale 오판 — CI가 ground truth, 브랜치 보호로 게이트 ★

- **맥락**: M0 PR에서 Gemini Code Assist가 3건을 지적(2건 CRITICAL).
- **사실**: 3건 모두 **오판**이었다 — ① "Spring Boot 4.1.0은 존재하지 않는 가상 버전"(실재, 2026 GA) ② "Boot 스타터 이름이 틀림"(Boot 4의 모듈형 스타터 재편을 모름) ③ "`PostgreSQLContainer` raw type → `<?>`"(새 모듈형 Testcontainers의 `org.testcontainers.postgresql.PostgreSQLContainer`는 **비제네릭**이라 제네릭 표기가 오히려 컴파일 에러). 모두 모델의 **stale 지식**.
- **검증**: 초록 CI가 ①②를 반증. ③은 적용했더니 `does not take parameters` 컴파일 에러로 드러나 즉시 원복.
- **2차 사고(내 실수)**: 검증 명령을 `gradlew | tail`로 파이프해 **실패 종료코드가 가려져** 깨진 커밋이 머지됨 → 핫픽스(PR #3)로 복구.
- **결정·교훈**:
  1. AI 리뷰도 "그럴듯하지만 검증되지 않음"일 수 있다 — 사람이 **CI/컴파일로 판정**한다(리뷰의 확신이 아니라).
  2. 파이프로 종료코드를 가리지 않는다(`set -o pipefail` 또는 파이프 금지).
  3. **브랜치 보호로 "CI 통과 + PR 필수"를 강제**해 broken merge를 구조적으로 차단(이 사고 직후 적용).
- **AI 활용 관점**: 이 에피소드 자체가 [`../ai-collaboration/methodology.md`](./methodology.md)·[`verification-loop.md`](./verification-loop.md)의 실증 — **검증을 "리뷰의 그럴듯함"이 아니라 "CI·DB 제약"으로 옮긴다**는 원칙이 실제 사고에서 작동했다.

## ADR-008. 교차 행 불변식(주문 총액 = Σ라인 금액)을 DEFERRABLE 제약 트리거로 강제 ★

- **맥락**: 주문(order) DDL. "주문 총액 == Σ라인 금액"은 이 도메인의 핵심 불변식인데, **여러 라인에 걸친 합**이라 단일 행 `CHECK`로는 못 박는다.
- **대안 검토**:
  - **(A) 헤더에 total을 두지 않고 `SUM(order_line)`으로 파생** — 자명하게 정합하지만, `total_amount`는 결제 confirm 때 PG `totalAmount`와 대조하는 **load-bearing 스냅샷**(브라우저 조작 방지)이라 헤더에 동결값이 필요. 탈락.
  - **(B) 애플리케이션 코드에서 합 검증** — 우회 경로(다른 코드·AI가 짠 경로)가 생기면 뚫린다. AGENTS.md "불변식은 구조로 박제" 위반. 탈락.
  - **(C) DEFERRABLE INITIALLY DEFERRED 제약 트리거** — **채택**. 헤더 INSERT→라인 INSERT의 자연스러운 순서를 한 TX로 허용하면서, 커밋 시점에 헤더 total과 라인 합을 대조해 안 맞으면 TX 전체를 거부.
- **결정**: 단일 행은 `CHECK`(수량>0, 단가≥0, `line_amount = unit_price×quantity`), 교차 행은 (C). 트리거는 `RAISE ... USING ERRCODE='check_violation'`(23514)로 던져 Spring이 `DataIntegrityViolationException`으로 번역하게 함(결제 슬라이스와 동일 예외 계열).
- **테스트 함정과 검증**: 지연 제약은 **커밋에서만** 검사된다 → ① `@Transactional` 테스트는 롤백이라 **검사가 아예 안 돈다**(거짓 그린). ② 커밋 시점 실패는 `TransactionSystemException`으로 감싸진다. 그래서 테스트는 헤더+라인을 한 TX로 넣고 끝에서 `SET CONSTRAINTS ALL IMMEDIATE`로 검사를 그 자리에서 당겨 `DataIntegrityViolationException`으로 잡는다. 예제 IT + jqwik property(헤더를 일부러 틀리게 한 tamper 케이스 포함) 둘 다 실 Postgres로 검증.
- **경계**: `order_line.order_id → orders(id)`는 같은 BC라 FK로 무결성을 챙기고, `seller_id`(외부 셀러 도메인)·`product_id`(카탈로그)는 값 참조로만 둔다 — 멀티셀러는 한 주문에 서로 다른 `seller_id` 라인이 공존하는 것으로 표현.
- **리뷰로 잡은 구멍(write skew)**: 최초 트리거는 라인 UPDATE 시 `NEW.order_id`(새 주문)만 검사 → 라인을 다른 주문으로 옮기면 **라인을 빼앗긴 OLD 주문은 합이 틀어진 채 통과**했다(CodeRabbit 지적). 도메인상 라인 재배치는 없으므로 `order_id`를 **불변으로 못박는 BEFORE UPDATE 트리거**(`forbid_order_line_reparent`)로 경로 자체를 봉쇄하고, 이를 거부하는 테스트(`reparentingLineRejected`)를 회귀로 추가. "AI 산출물을 적대적으로 검증"(ADR-005/007 계열)이 리뷰 단계에서 또 작동한 사례.

## ADR-009. 배송 상태는 append-only 이력 + 이벤트 멱등은 (event, seller) UNIQUE ★

- **맥락**: 배송(shipping) DDL. 결제확정(PaymentConfirmed) 이벤트로 셀러별 배송을 만들고, 배송 상태가 전이한다.
- **결정 1 — 상태를 UPDATE로 덮지 않고 append-only 이력으로**: `shipment.status`는 읽기 캐시일 뿐, 권위는 `shipment_event`(전이를 행으로 쌓음). 단일 `status` UPDATE는 ① 과거(언제·무엇에서·무엇으로 갔나) 소실 ② 동시 전이 lost update ③ 불법 역전이를 못 막는다. "현재 상태는 단 하나"는 **`most_recent` 부분 UNIQUE 인덱스**(`WHERE most_recent`)로 DB가 강제 — 전이는 "직전 most_recent를 false로 내리고 + 새 행을 true로"를 한 TX에서.
- **결정 2 — 이벤트 멱등은 `(source_event_id, seller_id)` UNIQUE**: 도메인 이벤트는 at-least-once라 결제확정이 중복 도착한다. `source_event_id` *단독* UNIQUE면 한 이벤트가 셀러별 배송 N건을 만드는 멀티셀러를 못 그린다 → **복합키**로 "이벤트당 셀러마다 1건"을 보장. 재수신/따닥은 23505로 흡수(멱등), 동시 16스레드 테스트로 실증.
- **결정 3 — cross-BC FK 비대칭(payment 선례 유지)**: `shipment_event.shipment_id → shipment(id)`는 같은 BC(배송)라 FK. 반면 `shipment.order_id`는 타 BC(주문)이라 **FK 없이 값 참조**(seller_id·product_id와 동급). 설계 문서(system-design)는 단일 DB라 cross-context FK를 허용하나, 구현은 "추출 가능성"을 위해 더 순수한 DDD 노선을 택해 payment(V2)와 일관되게 cross-BC FK를 걸지 않는다.
- **검증**: 실 Postgres로 멱등(예제+동시성+property)·멀티셀러·status CHECK·most_recent 단일성·sort_key 유일·FK를 모두 거부/허용 양쪽으로 확인. 과적재 방지(over-ship) 같은 라인 수량 게이팅은 order_line 카운터를 건드려 BC가 얽히므로 **M3(이벤트 소비) 슬라이스로 미룸**(Scope Out 명시).

## ADR-010. 정산 = 부호 있는 append-only 원장 + 주기 겹침 금지는 EXCLUDE(GiST) ★

- **맥락**: 정산(settlement) DDL. 이 프로젝트의 헤드라인 역량①(멀티셀러 정산 + 4-way 대사)의 뿌리. M1 마지막 도메인 슬라이스.
- **결정 1 — 부호 있는 append-only 원장**: 셀러 순지급은 잔액 컬럼이 아니라 `SUM(settlement_line.amount_minor)`로 도출(AGENTS.md "잔액 UPDATE 금지"). 판매는 `SALE(+)`·`COMMISSION(−)` 두 줄, 환불은 `REFUND(−)` 등으로 *새 줄 추가*. 정정도 UPDATE가 아니라 반대부호 `ADJUSTMENT`. 부호는 단일 축이라 복식부기(side D/C+양수)와 달리 부호 amount를 쓴다.
- **결정 2 — `ck_settline_sign`이 load-bearing**: 선형 SUM 모델은 부호가 양변에서 함께 뒤집히면 상쇄돼 통과하므로(드리프트), 부호 정합을 SUM이 아니라 **INSERT CHECK**(entry_type별 +/−/≠0)로 막는다. "1원 드리프트 봉쇄"의 DB 강제 지점. 출처당 SALE/COMMISSION 1줄은 부분 UNIQUE.
- **결정 2b — 멱등키는 `(source_event_id, entry_type, seller_id)` 복합**(리뷰 반영): `source_event_id` *단독* UNIQUE면 **fan-out을 중복으로 오인**한다 — 한 사건이 SALE(+)·COMMISSION(−)로 분기(entry_type 다름)하거나 PAYMENT_CANCEL이 멀티셀러로 분기(seller_id 다름)하면 두 번째 줄이 23505로 막힌다(CodeRabbit 지적). 복합키는 정상 fan-out은 허용하고 진짜 중복(같은 event+type+seller 재수신)만 흡수한다. fan-out 허용(타입/셀러)·진짜중복 거부를 양쪽 테스트로 실증.
- **결정 3 — 주기 겹침 금지는 EXCLUDE(GiST)**: 같은 셀러·유형의 정산 주기가 시간상 겹치면 매출이 이중 집계된다. "범위 겹침"은 단일 CHECK·UNIQUE로 못 박으므로 `EXCLUDE USING gist (seller_id WITH =, cycle_type WITH =, tstzrange(start,end,'[)') WITH &&)`로 DB가 강제(`btree_gist` 확장 필요 — = 와 && 혼용). 반열림 [start,end)라 인접 주기는 겹침이 아니다.
- **경계**: `settlement_line.cycle_id → settlement_cycle(cycle_id)`는 같은 BC라 FK. `seller_id`(외부 셀러)·`source_id`(타 컨텍스트 주문/결제 id)는 값 참조.
- **검증**: 실 Postgres로 부호(예제 0/±1 경계 + property 300)·겹침 거부/인접·타셀러·타유형 허용·복합 멱등·fan-out·출처 UNIQUE·FK를 양쪽으로 확인.
- **의도적 Scope Out — 누적환불 상한(`0 ≤ 누적환불 ≤ 결제금액`)은 정산이 아니라 결제 컨텍스트에서 강제**(CodeRabbit이 정산 DDL에 트리거로 내리라 제안했으나 *층이 다름*): 이 불변식은 *원결제 금액*을 참조해야 하는데 결제금액은 payment BC에 있다. 정산에서 강제하려면 결제금액 비정규화/cross-BC 트리거가 필요해 AGENTS.md 황금률1(BC 내부 참조 금지)을 위반한다. 이미 `payment.ck_payment_refund_range CHECK (refunded_amount <= paid_amount)`(V2)가 올바른 자리에서 강제하고, settlement_line은 그 환불 흐름이 검증한 사실을 기록하는 하류 원장이다. 라인↔원결제 누적 게이팅은 **M5 refund 슬라이스**로 위임. 마감 집계 캐시(`settlement`)·지급대행(`payout`)·수수료 정책(`commission_policy`)도 후속 슬라이스로 미룸.

## ADR-011. M2 애플리케이션 계층 전략 — 리치 JPA 엔티티 · 헥사고날-lite · 3층 검증 · 경량 BDD ★

- **맥락**: M2부터 DDL이 아니라 *도는 기능*(도메인+서비스+API+트랜잭션). 모델·레이어·테스트 전략을 정한다.
- **결정 1 — 도메인 모델 = 리치 JPA 엔티티**: 불변식은 *이미 DB에 박제*돼 있으므로(CHECK/트리거/UNIQUE/EXCLUDE) 도메인의 역할은 재구현이 아니라 ① 유효 객체만 생성(팩토리·private 생성자·빠른 피드백) ② 유비쿼터스 언어·행위 표현이다. 별도 도메인/영속 모델 분리는 이 규모에 매핑 보일러플레이트만 늘려 과하다 → JPA 엔티티에 행위 메서드·private setter·protected 기본생성자를 둔 *리치 엔티티*를 도메인 모델로. (트레이드오프: JPA가 도메인에 새어듦 — 수용)
- **결정 2 — 헥사고날-lite 레이어링**: 컨텍스트별 `domain`/`application`/`web`/`infra`. 외부 경계(PG·시계)는 포트 인터페이스 + Fake(Mockito verify 금지), DB·도메인은 실물(Testcontainers). 공통 모듈은 컨텍스트를 역의존하지 않음 — 예외 매핑은 `common.error.ResourceNotFoundException` 베이스로(컨텍스트→common 단방향).
- **결정 3 — 3층 검증(앱이 거르고, DB가 보장)**: 요청 DTO Bean Validation→400(빠른 피드백) · 도메인 불변식→400/409 · DB 제약→409(어떤 경로로 와도 막는 최후 보루). `@RestControllerAdvice`가 `ProblemDetail`로 매핑. "검증=UX, 제약=보장"의 역할 분리.
- **결정 4 — 경량 BDD 이중 루프**: 바깥 루프 = 골든 시나리오를 `@Nested`+`@DisplayName` Given/When/Then MockMvc 인수테스트(비즈니스 언어, 풀스택+실 DB) + `scenario.http` 살아있는 문서. 안쪽 루프 = 단위/통합/property TDD. Cucumber/Gherkin은 솔로 프로젝트에 글루코드 오버헤드가 과해 미채택.
- **결정 5 — cart는 보조 컨텍스트**: 정산 핵심 4 BC가 아니라 주문 전 staging. `cart_item`은 애그리거트 소유라 FK+`ON DELETE CASCADE`(transient — order_line의 RESTRICT와 의도적 대비). product_id/seller_id는 외부 값 참조.
- **Boot 4 메모(검증으로 발견)**: Jackson 3(`tools.jackson.*`, `com.fasterxml` 아님), `@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure`로 이동. 테스트는 ObjectMapper 대신 JsonPath로 응답 파싱(의존 최소화).
- **검증**: Cart 단위(불변식·upsert·멀티셀러) + DB 제약 IT(raw INSERT 우회 거부) + 경량 BDD 인수테스트(담기·누적·400·404) 전부 실 Postgres 그린.
- **리뷰로 잡은 3건(PR #9)**: ① **오퍼 키 = (product, seller)** — 최초 `productId`만으로 dedup해 *같은 상품의 다른 셀러 오퍼가 조용히 한 줄로 병합*(멀티셀러를 깨는 침묵 버그). 도메인 `findOffer(product, seller)` + DB `UNIQUE(cart_id, product_id, seller_id)`로 정정. ② **Money 오버플로우 fail-loud** — `long` 곱/합이 조용히 음수로 래핑 → `Math.multiplyExact/addExact`로 `ArithmeticException`(핸들러가 400 매핑). ③ **id 양수 가드** — `@Positive`(DTO) + 도메인(`createFor`/`CartItem.of`) 가드. (외부 id 양수 DB CHECK는 기존 테이블 전체가 안 거는 컨벤션이라 일관성 위해 보류.)

## ADR-012. S1 주문 생성 — cart→order 경계는 published 포트 + 카트 닫기 낙관적 락 ★

- **맥락**: `POST /orders`는 카트 내용을 읽어 멀티셀러 주문(order+order_line)으로 굳히고 카트를 닫아야 한다. 하지만 황금률 #1상 order BC는 cart의 내부 엔티티·테이블을 직접 참조하면 안 된다.
- **결정 1 — published 포트(A안)**: cart가 읽기 전용 계약 `cart.published.{CartSnapshot, CartForOrder}`를 노출하고, order는 이 포트만 의존한다(order→cart.published 단방향). 구현 `CartForOrderAdapter`는 cart 내부에 두어 order가 모른다. 대안 B(order가 cart_id로 직접 조회)는 BC 결합을, 또 다른 대안(상태를 필드로 들고 다니는 합성)은 HTTP/cart 개념의 도메인 누출을 일으켜 기각. **ArchUnit 규칙을 "BC 내부 상호 참조 금지, `..published..` 계약만 예외 허용"으로 정교화**해 이 경계를 테스트로 박제.
- **결정 2 — 읽기+닫기를 원자적 `consumeForOrder` 하나로**: cart를 `findByIdForUpdate`(OPTIMISTIC_FORCE_INCREMENT)로 한 번 락 로드해 ACTIVE 검증·닫기(ORDERED)·스냅샷 추출을 묶는다. 스냅샷과 닫힌 카트가 *같은 잠긴 로드*에서 나오므로 ① stale 주문 불가(읽기→닫기 사이 카트 수정이 끼면 version 충돌로 거부), ② 동시 이중 주문은 한쪽만 성공, ③ 이미 ORDERED면 주문 INSERT 전에 즉시 409(fail-fast). (초안은 `read`+`markOrdered` 2단계였으나 이중 조회·fail-late·읽기↔닫기 TOCTOU를 PR #16 리뷰가 지적 → 원자 단일 메서드로 수렴.)
- **의도적 Scope Out**: 동기 호출로 카트를 닫는다. 이벤트(outbox) 기반 비동기 디커플링은 M3로 위임. 재고 예약·결제 연계(S2)도 후속.
- **검증**: 도메인 단위(총액=Σ라인·멀티셀러·빈 주문 거부) + **도메인 property(총액=Σ라인, jqwik)** + 경량 BDD(멀티셀러 전환·409·400·404) + 동시성 IT(이중 주문 → 정확히 1건이고 패배는 경합 예외만 / 주문↔카트수정 race에서 주문 라인 = 닫힌 카트 항목) 전부 실 Postgres 그린. ArchUnit 3규칙 통과.
- **리뷰 반영(PR #16)**: 채택 — 원자 consumeForOrder + edit-vs-place IT, 패배 예외 타입 단언 강화, `CartSnapshot` 불변 복사(`List.copyOf`), 총액 property 추가. **거절** — (E) `@PathVariable` 명시 이름: Spring Boot 플러그인이 `-parameters`를 기본 적용하고 기존 cart 컨트롤러의 이름 없는 `@PathVariable`이 인수 테스트로 동작 증명됨 → 비이슈. (F) `OrderLineSpec.unitPrice`를 `Money`로: 단가 불변식은 이미 `OrderLine.of` + DB CHECK로 강제되고 현재 `Money(long)`는 음수를 거부하지 않아 안전 이득이 없으며 published 경계는 의도적으로 primitive를 쓴다 → 다통화로 Money가 리치 VO가 될 때 재검토.

## ADR-013. S2 결제 확정 — 트랜잭셔널 아웃박스 + 3겹 멱등 + PG 포트 ★

- **맥락**: `POST /payments`(선점) → `POST /payments/{id}/confirm`(PG 확정). confirm은 DB 상태 변경(PAID)과 이벤트 발행을 함께 해야 하는데, 둘을 한 트랜잭션으로 못 묶으면 "결제는 됐는데 후속(배송/정산) 이벤트는 유실"이 난다.
- **결정 1 — 트랜잭셔널 아웃박스(ADR-002 적용)**: confirm이 `payment` UPDATE와 `outbox` INSERT를 같은 @Transactional로 원자화한다(2PC 없이). 실제 발행 릴레이·소비는 M3로 위임, 여기선 적재까지. outbox는 `common.outbox`(공유 인프라)에 두고 `OutboxAppender`(JdbcClient, 호출자 TX 합류)로 적재.
- **결정 2 — 멱등 3겹**: ① confirm 시 이미 PAID면 no-op(PG 재호출 안 함) ② PG에 멱등키를 end-to-end 전달(`PaymentGateway.confirm(idemKey, amount)`) — 따닥/재시도에 PG가 중복 청구하지 않게 ③ `uq_outbox_event(aggregate_type, aggregate_id, event_type)`가 중복 적재의 최후 보루. 선점도 멱등키로 멱등(기존 반환, 동시 선점은 23505 재조회 흡수).
- **결정 3 — PG는 아웃바운드 포트 + Fake/Stub**: `PaymentGateway` 인터페이스(application) + `StubPaymentGateway`(infra, 멱등키 기반 결정적 승인). AGENTS.md "외부 경계만 mock"과 정합 — 실 PG 연동 시 이 빈만 교체.
- **의도적 Scope Out**: 주문 총액 교차검증(브라우저 조작 방지 — order published 포트 필요), 환불(M5), outbox 릴레이/발행·소비(M3), 결제 취소(CANCELLED 전이). amount는 요청값을 신뢰.
- **검증**: 도메인 단위(선점·전이·이중확정 거부) + 경량 BDD(선점→확정→PAID+outbox 1건, confirm/선점 멱등, 400/404) + 동시성 IT(따닥 16스레드 confirm → PAID 1회·outbox 1건, 패배는 DataIntegrityViolation) 전부 실 Postgres 그린.
- **리뷰 반영(PR #17) — AI 리뷰가 적중한 사례 ★**: Gemini Code Assist 3건 중 1건 적중. **채택** — ① [선점 멱등 정정 ★] 결정 2③의 "23505 재조회 흡수"가 실은 깨진 설계였다: `@GeneratedValue(IDENTITY)`라 동시 선점 시 save가 즉시 INSERT→23505로 Hibernate 세션이 null-identifier로 오염되고, catch 안 재조회가 flush를 트리거해 `org.hibernate.AssertionFailure`로 터진다(16스레드 재현: 7성공/9예외, payment는 1건 보장되나 멱등이 깨짐). `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING` + 무조건 키 재조회로 정정 — 충돌을 예외가 아닌 no-op으로 흡수해 세션 오염 자체를 없앤다(JdbcClient, OutboxAppender 선례·"DB 제약으로 불변식" 철학과 정합). 반례를 `PaymentInitiateConcurrencyIT`로 영구 회귀. ② outbox payload를 수동 문자열 포매팅→Jackson 직렬화(외부 PG가 준 `pgTransactionId`의 따옴표·역슬래시가 JSON을 깨뜨릴 위험). **거절** — confirm 비관적 락(FOR UPDATE): 정확성 버그가 아니라 효율성 제안이다. `uq_outbox_event`가 이미 중복 발행을 흡수하고(동시성 IT가 outbox 1건 입증) PG 멱등키로 중복 청구도 막혀, 남는 건 불필요한 PG 호출 1회뿐 — 핫로우 락 비용과의 트레이드오프라 보류. **메타 교훈**: ADR-007(Gemini 3건 전부 오판)과 달리 이번엔 1건 적중. 단 예측한 예외 타입(UnexpectedRollback)≠실제(AssertionFailure)였고 처방(try-catch 제거)은 멱등을 보장 못 해 불완전 → 추측이 아니라 동시 선점 테스트로 실증한 뒤 정정했다. "AI 리뷰도 가끔 맞다, 그러나 판정은 테스트가 한다".

## ADR-014. M3-a 이벤트→배송 — outbox 릴레이(in-process) + 셀러별 배송 멱등 생성 ★

- **맥락**: M2가 적재한 `PaymentConfirmed`(outbox)를 사후 발행해 배송 BC가 소비하고, 주문의 셀러별로 배송을 자동 생성해야 한다. 골든 시나리오 S3.
- **결정 1 — outbox 릴레이는 in-process 폴링 디스패치**: `@Scheduled` 폴링(`OutboxRelay`)이 `published_at IS NULL` 행을 `OutboxDispatcher`에 **건별로** 위임한다. 디스패처는 행을 `FOR UPDATE`로 잠그고 `published_at IS NULL`을 재확인한 뒤, 매칭 핸들러 호출과 발행 마킹을 **한 트랜잭션**으로 묶는다 — 핸들러가 던지면 마킹도 롤백돼 다음 폴링에서 재시도(at-least-once)된다. 외부 브로커 없이 같은 JVM에서 디스패치(modular monolith, ADR-004). 릴레이는 `@ConditionalOnProperty(outbox.relay.enabled)`로 토글 — **테스트는 폴링을 끄고 디스패처를 직접 호출**해 백그라운드 폴링의 비결정성을 제거한다.
- **결정 2 — 셀러 정보는 order.published 포트로(cart→order 선례)**: `PaymentConfirmed` 페이로드엔 셀러가 없다. order BC가 `order.published.OrderForShipping`(orderId→distinct seller_id) 읽기 계약을 노출하고 shipping이 소비한다(shipping→order.published 단방향, ArchUnit이 경계 강제). 대안(shipping이 `order_line`을 직접 조회)은 BC 경계를 약화시켜 기각 — ADR-012(order→cart.published)와 일관.
- **결정 3 — 배송 생성 멱등은 ON CONFLICT(payment.initiate 선례)**: at-least-once 재전달에 대비해 `INSERT ... ON CONFLICT (source_event_id, seller_id) DO NOTHING`으로 충돌을 예외 아닌 no-op으로 흡수. `source_event_id = "payment:{paymentId}"`(결제확정은 결제당 1건이라 불변·고유). `RETURNING`으로 *이번에 새로 만든* 배송만 식별해 그때만 초기 상태 이력(`shipment_event` READY, `most_recent`)을 append.
- **의도적 Scope Out(M3-b 이후)**: 배송 상태 전이(PICKED_UP→…→DELIVERED) API·전이 화이트리스트, 택배사 추적 콜백, 자동확정 타이머, 반품/교환.
- **검증**: 통합 IT(`ShipmentCreationIT`) — 멀티셀러 2건 생성+발행 마킹 / at-least-once 재전달 멱등(배송·상태이력 중복 0) / N회 재전달 수렴 / 이미 발행된 이벤트 재디스패치 no-op — 전부 실 Postgres 그린. 기존 `ShipmentConstraintsIT`·`ShipmentIdempotencyPropertyTest`(DB 제약)와 ArchUnit(BC 경계·순환 없음) 통과.

## ADR-015. M3-b 배송 상태 전이 — 화이트리스트 상태머신 + 핫로우 직렬화 ★

- **맥락**: `POST /shipments/{id}/events`로 배송 상태를 전이한다(골든 시나리오 S4). V4 DDL이 상태 문자열의 *도메인*만 CHECK로 막고 *전이의 합법성*(어느 상태→어느 상태)은 앱의 몫으로 남겨뒀다 — 그 자리를 채운다.
- **결정 1 — 전이 화이트리스트를 enum 구조로 박제**: `shipping.domain.ShipmentStatus`가 상태별 허용 후속을 `EnumMap<…, EnumSet>`으로 들고 `canTransitionTo`로 판정한다. 자가 전이·역전이·단계 건너뛰기·종료 상태(DELIVERED/FAILED/RETURNED)에서의 전이가 자료구조상 자동 거부된다 — if문 분기가 아니라 화이트리스트 한 곳이 단일 진실. 해피패스는 선형 전진이며 어느 단계서든 FAILED로 분기 가능. RETURNED는 반품 기능(로드맵) 전까지 도달 경로 없음. 불법 전이 → `IllegalStateException`(409).
- **결정 2 — append-only 전이를 shipment 행 FOR UPDATE로 직렬화**: 전이는 "잠금 획득 → 현재 most_recent 상태 읽기 → 화이트리스트 검증 → 직전 most_recent=false + 새 행 append(sort_key+1) → status 캐시 갱신"을 한 트랜잭션으로 한다. 같은 배송에 동시 전이가 와도 `SELECT … FOR UPDATE`가 직렬화하고, 패자는 *잠금 해제 후 갱신된 현재 상태를 다시 읽어*(READ COMMITTED) 자가 전이로 거부된다 — `most_recent` 부분 UNIQUE는 그 최후 보루. 잠금을 쥐기 전에 상태를 읽으면 lost update가 나므로 "잠금 먼저, 읽기 나중"(OutboxDispatcher 선례).
- **의도적 Scope Out**: 배송완료(DELIVERED) 시 정산-가능 신호(outbox 이벤트) 발행 — settlement가 소비하는 M4의 진입점이라 그쪽 슬라이스로 미룬다(이 PR은 shipping BC 1개 단위 유지). 택배사 추적 콜백·자동확정 타이머·반품/교환도 후속.
- **검증**: 단위(`ShipmentStatusTest`, 7×7 전이 쌍 전수 + 자가/역/스킵/종료) + 인수 IT(`ShipmentTransitionIT`, 해피패스 체인·FAILED 분기·불법 전이 409·404·400, append-only 불변식 DB 확인) + 동시성 IT(`ShipmentTransitionConcurrencyIT`, 16스레드 따닥 → 단 1건 성공·이력 2건) 전부 실 Postgres 그린.
- **리뷰 반영(PR #20)** — Gemini·CodeRabbit 지적을 적대적으로 재판정. **채택** — ① [응답 스냅샷 ★] 컨트롤러가 `recordTransition`(tx1) 뒤 `getShipment`(tx2)를 따로 호출해, 두 트랜잭션 사이 다른 전이가 커밋되면 응답이 *방금 기록한 상태가 아닌 더 나중 상태*가 될 수 있었다(read-your-writes 위반). `recordTransition`이 잠금을 쥔 같은 트랜잭션 안에서 `ShipmentView`를 조립해 반환하도록 올리고 컨트롤러는 그 값을 그대로 반환. ② [torn read] `getShipment`가 head(status)와 history를 두 SELECT로 읽어 READ COMMITTED에선 그 사이 커밋된 전이로 둘이 어긋날 수 있어, 그 조회만 `REPEATABLE READ`로 한 스냅샷에 고정(격리수준은 트랜잭션별 opt-in, PG 기본 READ COMMITTED는 불변). ③ [동시성 테스트 강화] `ShipmentTransitionConcurrencyIT`가 `DataIntegrityViolationException`(most_recent UNIQUE 최후보루)도 정상 거부로 세어, FOR UPDATE 직렬화가 깨져도 초록이었다 — DIVE를 별도 카운터로 분리해 `isZero()` 단언(잠금이 살아있으면 패자는 자가전이 `IllegalStateException`으로만 거부). ④ [반례 강화] 불법 전이 IT 4종이 409만 보고 no-op을 안 봐서, 실패 경로의 부분 쓰기 회귀를 못 잡았다 — 각 409 뒤 상태·이력 길이·most_recent 단일성 불변을 `assertUnchanged`로 검증. ⑤ [주석 규율] `RecordEvent`의 구현 메모를 javadoc→라인주석으로(§6.4). **거절/보류** — ⑥ 전이 멱등키(`source_event_id`): 이 경로는 동기 REST API라 at-least-once 재전달 원천이 없고, 그 원천인 택배사 추적 콜백은 이 슬라이스가 명시적으로 scope-out했다. 콜백 슬라이스에서 함께 넣기로 하고 코드에 근거 주석만 남김(로드맵 밖 선구현 금지). ⑦ **전이 합법성의 DB 강제(트리거)**: `shipment_event` write 경로가 `ShipmentService` 단 하나이므로 전이 화이트리스트는 앱 계층에서만 강제한다(§1.2 "DB가 최후 보루"는 `most_recent` 부분 UNIQUE + status/from_status CHECK로 이미 부분 충족). **두 번째 write 경로가 생기면 `(from_status = 직전 most_recent.to_status)` 체인 무결성 + 전이쌍 화이트리스트를 BEFORE INSERT 트리거로 추가한다** — 지금 넣으면 enum 화이트리스트를 SQL로 이중 유지해야 해 트레이드오프가 크다. 리스크 수용, 반품(RETURNED)/택배사 콜백로 write 경로가 늘 때 재검토.
