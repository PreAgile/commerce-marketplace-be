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
- **결정 2 — 카트 닫기에 낙관적 락 재사용**: `CartForOrder.markOrdered`가 cart의 `findByIdForUpdate`(OPTIMISTIC_FORCE_INCREMENT)를 타 동시 이중 주문을 cart 행에서 직렬화한다. read→save(order)→markOrdered가 한 트랜잭션이라, 카트 닫기 실패(이미 ORDERED=409 / 동시 충돌)시 주문도 함께 롤백 — 부분 전환이 남지 않는다.
- **의도적 Scope Out**: 동기 호출로 카트를 닫는다. 이벤트(outbox) 기반 비동기 디커플링은 M3로 위임. 재고 예약·결제 연계(S2)도 후속.
- **검증**: 도메인 단위(총액=Σ라인·멀티셀러·빈 주문 거부) + 경량 BDD(멀티셀러 전환·409·400·404) + 동시성 IT(N스레드 이중 주문 → 정확히 1건) 전부 실 Postgres 그린. ArchUnit 3규칙 통과.
