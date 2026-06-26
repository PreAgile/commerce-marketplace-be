# commerce-marketplace-be

> 멀티셀러 커머스 마켓플레이스 백엔드 — **정산·대사·분산 정합성**을 깊게 판 포트폴리오 프로젝트.
> AI(Claude)를 적극 활용하되, **AI가 못 어기게 구조로 박제하고 검증 가능한 명세로 채점**하는 방법론을 실천했습니다.

![status](https://img.shields.io/badge/status-WIP-orange)
![java](https://img.shields.io/badge/Java-21-blue)
![spring](https://img.shields.io/badge/Spring%20Boot-3.x-green)
![license](https://img.shields.io/badge/license-MIT-lightgrey)

> 🚧 **현재 단계**: 설계·문서 완료, 구현 진행 중. 각 섹션의 구현 상태를 함께 표기합니다.

---

## 한 줄 소개

비전은 **풀 커머스 마켓플레이스 플랫폼**(주문·결제·배송·정산·유저·실시간배송·지도)이지만,
첫 구현은 **돈 한 줄기를 end-to-end로 깊게** 팝니다 — 넓이보다 깊이를 택했습니다.

```
장바구니 → 주문 → PG결제 → 배송 → 멀티셀러 정산 → 부분환불 → 4-way 대사
```

> 확장 로드맵(유저·인증·실시간배송·지도·쿠폰·교환)은 [`docs/limitations-and-roadmap.md`](./docs/limitations-and-roadmap.md).

## 무엇을 증명하나 (4가지 역량)

| # | 역량 | 핵심 산출물 |
|---|---|---|
| ① | **멀티셀러 정산 + 4-way 대사** | 부호 append-only 원장, 미수금 이월, 4경로 대사 + drift 메트릭 |
| ② | **분산 정합성** | outbox 패턴(dual-write 회피), 멱등키, 이벤트 순서·상태머신 가드 |
| ③ | **AI 시대 테스트 전략** | Testcontainers 실 DB, property(jqwik), 결정론 시뮬, 런타임 대사·DB 불변식 |
| ④ | **동시성·스케일** | 핫로우 락·따닥 결제 제어, 측정 기반 마이크로서비스 분리 설계 |

## 아키텍처

4개 Bounded Context(주문·결제·배송·정산)를 코드·이벤트로 분리한 **modular monolith**.
컨텍스트 맵·DDL·골든 시나리오는 [`docs/design/system-design.md`](./docs/design/system-design.md).

```
[구매자] ─주문─▶ ORDER ─확정─▶ PAYMENT ─(outbox)─▶ SHIPPING
                  │                                   │
                REFUND ◀─부분환불─            배송완료 이벤트 → SETTLEMENT(배치)
                  │                                   │
                  └──────────▶ RECON(4-way 대사) ◀────┘   drift == 0 ?
```

## 핵심 설계 결정 (자랑 포인트)

- **부호 append-only 원장** — 순서 역전 문제를 *구조적으로 녹임* (잔액 컬럼 UPDATE ❌)
- **outbox로 dual-write 해결** — 2PC 없이 원자성 + at-least-once 멱등
- **4-way 대사** — 원장 SUM ↔ PG파일 ↔ 복식부기 ↔ 은행, drift 상태 모델로 정상/사고 구분
- **Mock은 외부 경계만** — DB·도메인은 진짜(Testcontainers)
- **측정이 분리를 정당화** — 조기 마이크로서비스 ❌, 병목을 데이터로 발견

근거와 트레이드오프는 [`docs/design/`](./docs/design/)에 상세.

## 🤖 AI로 어떻게 만들었나

이 프로젝트는 AI를 단순 코드 생성기가 아니라 **적대적 검증 파트너**로 썼습니다:

- **DDD 경계 = AI 가드레일** / **TDD 테스트 = AI 채점 기준** → "좋은 코드 짜줘"가 아니라 "이 테스트를 통과시켜라"
- **검증 루프** — AI 코드는 "그럴듯함"이 아니라 **테스트 그린 + DB 불변식 위반 0 + 대사 drift 0**으로만 신뢰
- **자기 적대 검증** — AI를 시니어 면접관으로 세워 자기 설계를 공격하게 해 실제 오류를 발견·정정 (예: `FOR SHARE` 락 오류, drift 알람 오류)

→ [`docs/ai-collaboration/`](./docs/ai-collaboration/) — [방법론](./docs/ai-collaboration/methodology.md) · [검증 루프](./docs/ai-collaboration/verification-loop.md) · [결정 기록(ADR)](./docs/ai-collaboration/decision-log.md)

## 직접 돌려보기  🚧 *구현 후 활성화*

```bash
docker compose up -d        # Postgres
./gradlew bootRun           # 앱
# Swagger:  http://localhost:8080/swagger-ui
# 골든 시나리오: scenario.http 를 위에서 아래로 실행 (S1~S8)
```

## 테스트·부하 전략

- **정확성**: 단위·통합(Testcontainers 실 DB) + property(jqwik) + 결정론 시뮬 + 런타임 대사
- **성능**: k6로 Load/Stress/Soak, knee 탐색 → 병목을 그래프로 ([`docs/load-testing/`](./docs/load-testing/), [`design/implementation-spec.md`](./docs/design/implementation-spec.md) §7)

## 한계와 로드맵

무엇을 의도적으로 안 했고, 무엇이 검증 미완이며, 어디로 갈지 → [`docs/limitations-and-roadmap.md`](./docs/limitations-and-roadmap.md)

## 라이선스

MIT
