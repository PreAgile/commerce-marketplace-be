# AGENTS.md — 이 코드베이스 작업 규칙 (AI·기여자 공통)

이 저장소에서 코드를 만들거나 고칠 때 반드시 따르는 규칙입니다. 설계 배경은 [`docs/`](./docs/)에 있습니다.

## 0. 프로젝트가 무엇인가

멀티셀러 커머스 마켓플레이스 백엔드. **비전은 풀 커머스 플랫폼**(주문·결제·배송·정산·유저·실시간배송·지도)이지만,
**현재 구현은 "돈 한 줄기"를 깊게**: `장바구니 → 주문 → 결제 → 배송 → 멀티셀러 정산 → 부분환불 → 4-way 대사`.
넓이보다 깊이가 우선이다. 로드맵 밖 기능을 임의로 추가하지 말 것([`docs/limitations-and-roadmap.md`](./docs/limitations-and-roadmap.md)).

## 1. 황금률 (타협 불가)

1. **DDD 경계를 넘지 마라** — 4개 Bounded Context(order·payment·shipping·settlement)는 서로의 *내부 테이블·엔티티를 직접 참조하지 않는다*. 컨텍스트 간은 **이벤트(outbox) 또는 명시적 id 참조**로만. 한 작업은 **BC 1개 단위**로 한다.
2. **불변식은 구조로 박제한다** — 돈·정산·환불 불변식은 코드 if문이 아니라 **타입·값객체·private 생성자 + DB 제약(CHECK/UNIQUE/EXCLUDE)** 으로 강제. 우회 경로를 새로 짜도 DB가 막아야 한다.
3. **테스트 먼저, 테스트가 명세다** — 돈·멱등·상태머신은 TDD. "좋은 코드"가 아니라 *통과해야 할 테스트*를 기준으로 작업한다.
4. **Mock은 외부 경계만** — DB·도메인 로직은 **절대 mock하지 않는다**(Testcontainers 실 Postgres). PG·택배·시계(Clock)만 mock. 상태 검증을 쓰고 `verify(interaction)`은 지양.
5. **완료의 정의** — "그럴듯해 보임"은 완료가 아니다. **테스트 그린 + DB 불변식 위반 0 + 대사 drift 0** 일 때만 완료. ([`docs/ai-collaboration/verification-loop.md`](./docs/ai-collaboration/verification-loop.md))

## 2. 테스트 규칙

- **계층**: 단위 → 통합(Testcontainers 실 DB) → property(jqwik) → 동시성 → 결정론 시뮬.
- **property test**: 돈 항등식이 있는 곳(정산 보존, 0≤누적환불≤결제, 멱등)은 예제 테스트 + property를 함께.
- **동시성**: 따닥 결제·핫로우 락·마감 race는 `CountDownLatch` N스레드 테스트로 실제 경합을 재현.
- **결정론 시뮬**: 시계·난수·외부 응답을 주입하고 시드로 고정. 깨지면 시드를 회귀 테스트로 남긴다.
- 새 불변식·버그는 **반례를 테스트로 추가**한 뒤 고친다(영구 회귀).

## 3. DB·불변식 규칙

- 스키마 변경은 **Flyway 마이그레이션**으로만(`src/main/resources/db/migration`). 기존 마이그레이션 수정 금지, 새 파일 추가.
- 정산 원장은 **부호 있는 append-only**(잔액 컬럼 UPDATE 금지). 잔액은 `SUM`으로 도출.
- 멱등은 `source_event_id`/idempotency_key **UNIQUE**로. 중복 INSERT는 23505 swallow + ack.
- 결정 배경은 [`docs/ai-collaboration/decision-log.md`](./docs/ai-collaboration/decision-log.md)(ADR) 참조. 핵심 결정을 바꾸면 ADR을 추가/갱신한다.

## 4. AI 작업 방식

- AI 산출물은 **적대적으로 검증**한다 — 작성 후 "이 설계가 어디서 틀리나"를 스스로 공격(이미 `FOR SHARE`·drift 알람 오류를 이 방식으로 잡았다, decision-log ADR-005/006).
- 컨텍스트 1개 = 작업 1단위. 남의 BC 내부를 모르는 채로 이벤트 페이로드만 보고 작업.
- 모호한 지시 금지. "이 테스트를 통과시켜라" 같은 검증 가능한 목표로 작업한다.

## 5. 기술 스택·컨벤션

- **Java 21 / Spring Boot 4.1.x / Gradle 9.x(Kotlin DSL) / PostgreSQL 16 / Flyway / Testcontainers / jqwik / k6**
- 패키지: `com.lemong.marketplace.<context>` (order/payment/shipping/settlement/common).
- 로컬 인프라: `docker compose up -d` (Postgres). 외부(PG·택배)는 mock.
- **커밋**: Conventional Commits(`feat:`·`fix:`·`docs:`·`test:`·`chore:`), 메시지는 한국어. 작은 단위로 자주.
- **언어**: 문서·커밋·설명은 한국어(존댓말). 코드 식별자·기술 용어는 원문 유지. 주석 규율은 §6.

## 6. 코드 질감 — 주석·가독성 규율

원칙: **코드가 1차 문서다. 주석은 코드가 *스스로 말 못 하는 것*만 적는다.** 유지보수성은 읽는 사람의 주의(attention)를 진짜 중요한 곳에만 쓰게 하는 데서 나온다. 주석이 많을수록 정작 중요한 한 줄이 잡음에 묻히고, 주석은 코드와 어긋나는 순간 거짓말이 된다. "잘 다듬어 보이는" 균일한 doc이 곧 AI 냄새이자 잡음이다.

1. **이름을 반복하지 마라.** `total()`에 "전체 합계", `addItem`에 "항목을 담는다"는 정보량 0 → 삭제. 의도는 이름·타입·구조로 드러낸다.
2. **"무엇"이 아니라 "왜"를 적어라.** 살릴 가치가 있는 건 비직관적 선택, 트레이드오프, 함정, 외부 제약(DB 제약·프레임워크 동작)과의 연결뿐. 예: "자식만 바뀌어도 루트 version을 올려야 해서 FORCE_INCREMENT".
3. **결정 배경은 코드가 아니라 ADR/docs에.** 코드 주석엔 짧은 포인터만(`decision-log ADR-00x`). 인라인 변명을 늘어놓지 마라.
4. **공개 API 계약만 javadoc(`/** */`), 구현 메모는 라인주석(`//`).** 내부 구현 노트를 javadoc으로 부풀리지 않는다.
5. **산문 틱 금지.** 과한 엠대시(—), 괄호 보충설명 남발, `*별표강조*`, 번역투. 평서문 한 줄로.
6. **정성을 불균등하게 써라.** 위험한 핵심(돈·동시성·상태머신·멱등)엔 깊게, 자명한 CRUD·게터·DTO엔 침묵한다. 모든 메서드에 똑같은 분량의 doc을 다는 균일함이 잡음이다.
7. **의도적 단순함은 한 줄로 방어하라.** "카트 항목 수는 작아 선형 스캔으로 충분 — 맵 인덱싱은 과설계"처럼, 누군가 '왜 최적화 안 했지?' 할 곳에만 이유를 남긴다.
8. **죽은 주석·TODO·주석처리된 코드 금지.** 추적 가능한 형태(이슈 링크)거나 제거.

판정 기준 한 줄: *"이 주석을 지우면 미래의 유지보수자가 무엇을 잃는가?"* — 답이 "아무것도"면 지운다. 단, `docs/`·ADR·학습 가이드 문서는 이 규율의 예외다(거기선 "왜"를 충분히 설명한다 — 무게는 코드가 아니라 문서가 진다).

## 7. 하지 말 것

- DB·도메인 로직 mock ❌ / 잔액 컬럼 직접 UPDATE ❌ / BC 내부 직접 참조 ❌
- 로드맵 밖 기능 임의 추가 ❌ (스코프 확장은 [`docs/limitations-and-roadmap.md`](./docs/limitations-and-roadmap.md) 갱신과 함께)
- "그럴듯함"으로 완료 판정 ❌ / 마이크로서비스 조기 분리 ❌(측정이 정당화할 때만)
- 이름을 반복하는 주석·모든 메서드 균일 doc·산문 틱 ❌ (§6 — 잡음이 진짜 신호를 묻는다)
