# 부하 테스트 실측 결과

> 🚧 *실행 대기*. [`../design/implementation-spec.md`](../design/implementation-spec.md) §7-3b의 실측 프로토콜대로
> 핫로우 락·outbox lag을 깨뜨려 knee·원인·대조군 효과를 여기에 그래프로 기록합니다.

## 실험 1 — 핫로우 락 경합

| knee 지점 | 그때 p99 | 국소화된 원인 | 대조군(행 분산) 효과 |
|---|---|---|---|
| _ VU / _ TPS | _ ms | pg_locks 대기 _% | knee 소멸 여부 |

## 실험 2 — outbox 릴레이 lag

| 유입 TPS | lag | 미발행 행 추이 | 대조군(파티션 분할) 효과 |
|---|---|---|---|
| _ TPS | _ s | 우상향 기울기 | 평탄화 여부 |
