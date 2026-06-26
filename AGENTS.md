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

- **Java 21 / Spring Boot 3.x / Gradle(Kotlin DSL) / PostgreSQL 16 / Flyway / Testcontainers / jqwik / k6**
- 패키지: `com.lemong.marketplace.<context>` (order/payment/shipping/settlement/common).
- 로컬 인프라: `docker compose up -d` (Postgres). 외부(PG·택배)는 mock.
- **커밋**: Conventional Commits(`feat:`·`fix:`·`docs:`·`test:`·`chore:`), 메시지는 한국어. 작은 단위로 자주.
- **언어**: 문서·커밋·설명은 한국어(존댓말). 코드 식별자·기술 용어는 원문 유지. 주석은 주변 코드 밀도에 맞춘다.

## 6. 하지 말 것

- DB·도메인 로직 mock ❌ / 잔액 컬럼 직접 UPDATE ❌ / BC 내부 직접 참조 ❌
- 로드맵 밖 기능 임의 추가 ❌ (스코프 확장은 [`docs/limitations-and-roadmap.md`](./docs/limitations-and-roadmap.md) 갱신과 함께)
- "그럴듯함"으로 완료 판정 ❌ / 마이크로서비스 조기 분리 ❌(측정이 정당화할 때만)
