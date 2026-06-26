<!-- 이 템플릿은 AGENTS.md의 "완료 정의"를 PR마다 강제하기 위한 것입니다. -->

## 변경 요약
<!-- 무엇을, 왜 -->

## 관련 컨텍스트 / 이슈
<!-- 어떤 Bounded Context(order/payment/shipping/settlement/common)인가 -->

## 완료 정의 체크리스트
> "그럴듯함"이 아니라 아래가 충족될 때만 머지합니다.

- [ ] 테스트 그린 (단위 · 통합 Testcontainers 실 DB · property · 동시성)
- [ ] 도메인 불변식을 **구조로 강제**했다 (해당 시 DB CHECK/UNIQUE/EXCLUDE 추가)
- [ ] 정합성/대사 영향 검토 (해당 시 drift 0 유지)
- [ ] **DDD 경계 준수** — 다른 BC 내부를 직접 참조하지 않음 (이벤트/id 참조만)
- [ ] **DB·도메인 mock 안 함** — 외부 경계(PG·택배·Clock)만 mock
- [ ] 마이그레이션은 **새 파일만** 추가 (기존 마이그레이션 수정 없음, 해당 시)
- [ ] 스코프 확인 — 로드맵 안의 작업이다 (Scope Out 위반 아님, [`docs/limitations-and-roadmap.md`](docs/limitations-and-roadmap.md))

## AI 활용 메모
<!-- 이 변경에 AI를 어떻게 썼고, 무엇으로 검증했나 (테스트/대사/적대적 리뷰).
     docs/ai-collaboration/decision-log.md 에 남길 결정이면 ADR 추가 -->

## 비고
<!-- 리뷰어가 알아야 할 트레이드오프·한계 -->
