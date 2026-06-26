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
