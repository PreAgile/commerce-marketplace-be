# PR #24 리뷰 정리 — 정산 사이클 마감 배치 + 셀러 조회 (S5, M4-c)

> 대상 PR: `feat(settlement): 정산 사이클 마감 배치 + 셀러 조회 (S5, M4-c)`
> 리뷰어: Gemini Code Assist(2건: high 1 · medium 1) · CodeRabbit(8건: Major 2 · nitpick 6) · CI Build & Test(pass) · CodeRabbit(pass)
> 이 문서는 봇 리뷰를 AGENTS.md 규칙에 비춰 **적대적으로 재판정**한 결과다(§4). 지적을 그대로 수용하지 않고, 사실관계·트레이드오프를 검증한다.

관련 파일: `settlement/application/SettlementCloseService.java` · `settlement/application/SettlementQueryService.java` · `settlement/application/SellerSettlementView.java` · `settlement/web/{AdminSettlementController,SellerSettlementController}.java` · 스키마 `db/migration/V5__settlement.sql`

---

## 처리 요약

| # | 이슈 | 리뷰어 | 심각도 | 판정 | 상태 |
|---|------|--------|--------|------|------|
| 1 | **TOCTOU 고스트 사이클** — 라인 0개짜리 CLOSED 사이클이 (seller,type,기간) 영구 봉인 | CodeRabbit | 🟠 Major | ✅ 수용 | **DONE** — 가드 + 8/8 재현 회귀 |
| 2 | 읽기 모델 방어복사 없음(`List.copyOf`) | CodeRabbit | 🔵 nitpick | ✅ 수용 | **DONE** — ADR-012 선례 |
| 3 | IDOR — `/sellers/{id}/settlements` 소유권/관리자 인가 없음 | CodeRabbit | 🟠 Major | 📌 defer | 전역 인증 부재(로드맵), 이 경로만 가드는 부정합 |
| 4 | `CycleSummary`(app record) API 노출 | CodeRabbit | 🔵 nitpick | 🤔 거절 | OrderView/ShipmentView 노출 관례와 동급 |
| 5 | N+1 → writable CTE 단일문 | Gemini | 🔴 high | 📌 defer | 배치 경로, ADR-004 측정 우선 · 고스트도 못 막음 |
| 6 | 조회 쿼리 서브쿼리-후-조인 재작성 | Gemini | 🟡 medium | 📌 defer | 소량 데이터, 측정 우선 |
| 7 | 셀러당 3회 왕복 → `RETURNING`으로 2회 | CodeRabbit | 🔵 nitpick | 📌 defer | 배치 마이크로-opt, #5와 같은 결 |
| 8 | 대량 셀러 단일 장기 트랜잭션 | CodeRabbit | 🔵 nitpick | 📌 defer | 부분 마감 방지 의도(ADR-018), 청크는 후속 |
| 9 | 테스트 픽스처 3파일 중복 | CodeRabbit | 🔵 nitpick | 🤔 거절 | 테스트 지역성 우선, 기존 관례와 일관 |

> **CI**: Build & Test(Testcontainers 실 DB) **pass** + CodeRabbit **pass**. 정확성 회귀는 **#1 하나**(적중, 수용). 나머지는 방어복사(#2)·인가(#3, 스코프)·성능/스타일(#4~9).
> **적용 범위**: 프로덕션 로직 변경은 **#1 고스트 가드 + #2 방어복사** 둘. 나머지는 defer/거절(근거는 각 절 + ADR-018).

---

## 1. TOCTOU 고스트 사이클 (✅ 수용) ★ — 이번 PR 최대 적중

**어디서:** `SettlementCloseService.run` — 후보 셀러 조회(67–71) → 셀러별 사이클 INSERT(75–80) → 라인 UPDATE(82–85).

**무엇이 문제인가:** `EXCLUDE ex_cycle_no_overlap`는 `(seller_id, cycle_type, 기간범위)`가 키다 — **`cycle_type`이 다르면 겹쳐도 안 막는다.** 그래서 이런 경합이 열린다:

```
seller S의 미배정 라인 L1,L2 (cycle_id NULL)
A: close(WEEKLY,  W)          B: close(EXPRESS, W)
① 후보조회 → [S]              ① 후보조회 → [S]   (둘 다 L1,L2 NULL로 봄)
② INSERT weekly cycle CA      ② INSERT express cycle CB  (유형 달라 EXCLUDE 통과)
③ UPDATE L1,L2 → CA (2건)     ③ UPDATE ... → CA 락 대기 → A커밋 후 재평가 → 0건
                              ④ CB는 라인 0개 CLOSED로 커밋 = 고스트
```

고스트 CB는 라인이 없는데도 `(S, express, W)`를 EXCLUDE로 **영구 봉인**해 그 창의 재마감을 막고, 조회 읽기 모델에도 net 0·lineCount 0 사이클로 노출된다. `ex_cycle_no_overlap`은 *기간 겹침*만 막을 뿐 *빈 사이클 생성*은 못 막는다.

**적대적 검증(재현):** 가드를 뺀 채 8스레드 크로스-타입 동시 마감을 돌려 **8/8 고스트 재현**. 반례를 `SettlementCloseConcurrencyIT.concurrentCrossTypeCloseLeavesNoGhostCycle`로 박제(불변식: 라인 0개 CLOSED 사이클 count == 0). 가드 복원 후 5/5 그린.

**판정: 타당. 수용.** AGENTS.md §1.2("우회 경로를 새로 짜도 DB가 막아야 한다")의 정신에 정확히 걸리는 갭이다. 다만 실서비스 발생 조건(한 셀러의 같은 미배정 풀을 *서로 다른 cycle_type*으로 동시 마감)은 현재 로드맵의 정상 흐름이 아니라 — 그래도 방어는 값싸고 조회 청결에 이득이라 defense-in-depth로 넣는다.

**해결책(적용):** UPDATE가 0건이면 방금 만든 사이클을 같은 TX에서 DELETE하고 건너뛴다.

```java
if (assigned == 0) {
    jdbc.sql("DELETE FROM settlement_cycle WHERE cycle_id = :cid").param("cid", cycleId).update();
    continue;
}
```

- `settlement_cycle`은 **기간 메타지 부호 원장이 아니라** 같은 TX 내 DELETE가 "잔액 컬럼 UPDATE 금지"와 무관하다(원장 = `settlement_line`은 손대지 않음).
- 이 경로가 `settlement_cycle`의 유일한 writer라 앱 가드로 충분(ADR-015 "write 경로 하나면 앱 강제"와 정합). **DB 트리거로 "빈 CLOSED 금지"**는 라인이 사이클 뒤에 붙는 순서 때문에 지연제약(DEFERRABLE)이 필요해 이 규모엔 과설계 → 보류(ADR-018 기록).
- **동시 같은-유형 마감**(기존 `concurrentCloseCreatesExactlyOneCycle`)은 INSERT 단계에서 EXCLUDE로 걸려 이 가드에 안 닿는다 — 두 방어가 서로 다른 경합을 덮는다.

---

## 2. 읽기 모델 방어복사(`List.copyOf`) (✅ 수용)

**어디서:** `SellerSettlementView`가 `JdbcClient.query(...).list()`(가변 `ArrayList`)를 그대로 보관.

**판정: 타당. 수용.** 읽기 모델(record)은 불변이어야 하는데 가변 리스트를 쥐면 밖에서 뒤집힐 수 있다. **ADR-012 `CartSnapshot`이 `List.copyOf`로 같은 처리를 한 선례**가 있어 일관성상 맞춘다. 값싸다.

```java
public SellerSettlementView {
    cycles = List.copyOf(cycles);
}
```

---

## 3. IDOR — 셀러 조회 인가 (📌 defer)

**리뷰 주장(CodeRabbit Major):** `/sellers/{id}/settlements`가 path의 `sellerId`를 그대로 `forSeller(id)`에 넘긴다. 호출자-셀러 매핑 검증이 없어 타인 정산 금액 조회(IDOR)가 가능하다.

**적대적 검증:** 절대적 관점에선 사실이다. 하지만 **이 저장소엔 아직 인증/인가 계층 자체가 없다** — `docs/limitations-and-roadmap.md`가 "유저·인증·인가(현재는 시드/헤더 stub)"를 명시적 로드맵 항목으로 둔다. order·payment·shipping의 **모든 엔드포인트가 동일하게 열려** 있고, 검증할 `Principal`/보안 컨텍스트가 존재하지 않는다.

**판정: defer.** 이 한 엔드포인트에만 소유권 가드를 붙이면 (a) 나머지와 부정합하고, (b) 기댈 인증 주체가 없어 구현 불가에 가깝다. 인가는 **횡단 관심사**라 유저·인증 슬라이스에서 `SecurityFilterChain` + 소유권/admin 정책으로 일괄 도입하는 게 맞다(스코프 규율: 로드맵 밖 선구현 금지). ADR-018 Scope Out에 "인증/인가"를 명시로 남겨 추적한다.

---

## 4. `CycleSummary`(app record) API 노출 (🤔 거절)

**리뷰 주장(CodeRabbit):** `AdminSettlementController.RunCloseResponse`가 `SettlementCloseService.CycleSummary`(마감 로직 반환 타입)를 그대로 노출한다. 서비스 내부가 바뀌면 API JSON 계약이 암묵적으로 흔들린다 → 웹 전용 DTO 분리 권장.

**적대적 검증:** 계약 안정성 논리는 일반론으론 옳다. 그러나 **이 코드베이스의 확립된 관례와 어긋난다** — `OrderController`는 `OrderView`(application)를, `ShipmentController`는 `ShipmentView`(application)를, 이 PR의 `SellerSettlementController`는 `SellerSettlementView`(application)를 **그대로 반환**한다(ADR-011 "리치 뷰를 응답으로"). `CycleSummary`만 웹 DTO로 분리하면 4개 컨트롤러 중 하나만 다른 패턴이 된다.

**판정: 거절(일관성).** 계약-전용 DTO 레이어를 도입하려면 **네 컨트롤러를 함께** 바꾸는 별도 결정이어야지, 이 슬라이스에서 하나만 예외로 두는 건 잡음이다. 지금은 application 뷰 = 응답 계약이 저장소 규약이다.

---

## 5·6·7·8. 성능·확장 계열 (📌 defer)

정산 마감은 **OLTP가 아니라 배치**다. ADR-004("측정이 정당화할 때만 최적화·분리")에 따라 현재 규모에선 단순·명료를 우선하고, 병목은 부하 실측(로드맵 §7)이 정당화할 때 진화한다.

- **#5 writable CTE 단일문(Gemini high):** N+1을 한 문장으로 줄이는 정공법이고 락 순서(`ORDER BY seller_id`)도 유지 가능하다. 그러나 (a) **크로스-타입 고스트(#1)를 못 막는다** — 단일 CTE라도 다른 cycle_type의 동시 마감은 다른 스냅샷/문장이라 여전히 빈 사이클을 만든다(가드는 별개로 필요). (b) 셀러별 루프가 ADR-018의 "INSERT→조건부 UPDATE 두 겹 방어" 서사를 훨씬 읽기 쉽게 드러낸다. (c) 배치라 3N+1이 OLTP p99에 안 실린다. → **defer**, 부하가 knee를 보이면 재검토.
- **#6 조회 서브쿼리-후-조인(Gemini medium):** `settlement_line`이 커지면 조인-후-그룹화보다 그룹화-후-조인이 싸다는 건 맞다. 소량 데이터·읽기 경로라 지금은 가독성 우선. 읽기 모델은 어차피 CQRS/read replica가 로드맵이라 그때 함께.
- **#7 `RETURNING`으로 SELECT SUM 제거(CodeRabbit):** 셀러당 3→2 왕복. #5와 같은 배치 마이크로-opt. `SELECT SUM`이 "net = 부호 원장 합" 서사를 더 또렷이 보여줘 현재는 유지.
- **#8 단일 장기 트랜잭션(CodeRabbit):** 정확히 ADR-018의 **의도된 설계**다 — 한 창 마감을 원자로 묶어 부분 마감(일부 셀러만 CLOSED)을 원천 차단한다. 청크/타임아웃/모니터링은 대량 셀러가 실측으로 정당화되면 넣는다(Scope Out 명시).

---

## 9. 테스트 픽스처 중복 (🤔 거절)

**리뷰 주장:** `insertLine`/`sellerSale`이 3개 IT에 복붙 → 공통 픽스처 추출 권장.

**적대적 검증:** 사실이다. 그러나 이 저장소의 테스트 관례는 **각 IT가 자기 픽스처를 지역적으로 소유**한다(shipping의 `seedReadyShipment`도 여러 IT에 중복). 테스트 지역성(한 파일만 봐도 셋업이 보임)이 DRY보다 우선이라는 암묵 규약이다.

**판정: 거절(지역성).** 공통 베이스/유틸로 빼면 결합이 생기고, `settlement_line` 스키마는 자주 바뀌지 않는다(부호 원장은 안정 계약). 세 IT의 중복 3줄은 감내 가능한 비용.

---

## 결론

- **CodeRabbit(8건)**: 정확성 적중 **1건**(#1 TOCTOU 고스트, 수용·8/8 재현 회귀). 방어복사 1건(#2, 수용). Major 1건(#3 IDOR)은 전역 인증 부재라 스코프 defer. 나머지 4건(성능·DTO·중복)은 배치 특성·기존 관례로 defer/거절.
- **Gemini(2건)**: 정확성 회귀 0건 — 배치 경로 성능 제안(CTE·조인 재작성), ADR-004 측정 우선으로 defer.
- **적용:** 프로덕션 로직 **2곳**(고스트 가드 + `List.copyOf`) + 회귀 테스트 1개. 그 외는 근거와 함께 ADR-018·Scope Out에 박제.
- **메타 교훈:** 봇의 "Major"가 항상 최우선은 아니다(#3 IDOR는 진짜지만 스코프 밖). 반대로 **가장 값진 적중(#1)은 EXCLUDE 키에 `cycle_type`이 포함된다는 스키마 디테일에서 나온 크로스-타입 경합**이었다 — 내가 동시성 테스트를 "같은 창"만 짜서 놓친 사각이다. 봇 지적을 **재현 테스트로 실증한 뒤** 고친다는 규율(ADR-005/008/013)이 다시 작동했다.

---

## 심화 방어 — "정산 마감을 왜 이렇게 짰나?" (꼬리질문)

> 리뷰어/면접관이 이 PR의 마감 배치를 파고들 때를 상정한 깊이 있는 방어. 판정 기준은 이 아키텍처의 실제 제약 — **단일 Postgres · 부호 있는 append-only 원장 · deferred 귀속(라인이 NULL로 유입) · 돈 불변식 우선 · 배치 경로**.

<details>
<summary><b>Q0. 애초에 "정산 마감"이 무슨 일을 하나? 왜 필요한가?</b></summary>

배송완료 소비(M4-b)는 셀러 매출을 `settlement_line`에 **부호 있는 줄**로 쌓는다(SALE +, COMMISSION −). 하지만 이 줄들은 `cycle_id=NULL` — "언젠가 정산될 미배정 상태"다. **마감**은 특정 기간의 미배정 줄들을 **셀러별 정산 주기(`settlement_cycle`)로 묶어 확정**하는 일이다.

왜 묶나? 정산은 "매 배송마다 즉시 송금"이 아니라 **주기 단위 지급**(주간/월간)이다. 셀러에게 "6월 1주차 정산액 = 이 사이클에 묶인 줄들의 합"을 보여주고 그 단위로 지급대행(payout, 후속)한다. 마감은 그 **주기 경계를 긋고 줄을 귀속**시키는 배치다.

핵심: 마감은 **금액을 만들지 않는다.** 줄은 이미 있고(SALE/COMMISSION), 마감은 `cycle_id`만 채운다. 셀러 순지급은 여전히 `SUM(amount_minor)` 도출(잔액 컬럼 없음, ADR-010). "마감 = 금액 이동"이 아니라 "마감 = 소속 확정"이다.

</details>

<details>
<summary><b>Q1. 왜 라인을 유입 때 바로 사이클에 넣지 않고, NULL로 뒀다가 나중에 마감하나? (eager vs deferred)</b></summary>

두 모델이 있다:

- **eager-assignment**: 배송완료 소비가 라인을 넣을 때 "현재 열린 사이클"을 찾아 즉시 `cycle_id`를 박는다.
- **deferred-assignment(채택, ADR-017)**: 라인은 `cycle_id=NULL`로 유입되고, 별도 마감 배치가 나중에 스윕해 귀속한다.

deferred를 택한 이유:
1. **유입 경로가 단순·빠르다**: 배송완료 핸들러(at-least-once, 멱등)는 라인 INSERT만 하면 된다. "열린 사이클 찾기/없으면 만들기"를 유입 핫패스에 넣지 않는다.
2. **마감 정책이 유입과 분리된다**: "언제·어떤 기간으로 끊을지"는 운영 결정이다. 유입이 사이클 구조를 몰라도 되게 해 관심사를 가른다.
3. **락 표면이 준다**: eager는 유입마다 사이클 행을 건드려 경합을 만든다(그래서 ADR-005가 유입/마감 락 비대칭을 고민했다). deferred는 유입이 사이클을 안 건드려 그 경합 자체가 사라진다(→ Q3).

대가: 라인이 마감 전까지 "미배정(pending)"으로 떠 있다. 조회 읽기 모델이 이걸 `pendingNetMinor`로 노출한다. 정산은 배치성이라 이 지연이 허용된다.

</details>

<details>
<summary><b>Q2. 이중 마감(같은 기간 두 번)을 어떻게 막나? 코드에 <code>if 이미 마감?</code> 안 보이는데.</b></summary>

**코드 if가 아니라 DB 구조 두 겹으로 막는다**(AGENTS.md §1.2 "불변식은 구조로 박제").

- **① `ex_cycle_no_overlap` EXCLUDE(GiST)**: `(seller_id WITH =, cycle_type WITH =, tstzrange(start,end,'[)') WITH &&)`. 같은 셀러·유형의 기간이 겹치는 두 번째 `settlement_cycle` INSERT를 DB가 거부한다. 재실행이든 동시 실행이든 최대 1건만 커밋. `run()`이 `@Transactional`이라 거부되면 그 배치 전체가 롤백돼 **부분 마감이 안 남는다**.
- **② `WHERE cycle_id IS NULL` 조건부 UPDATE**: 라인 귀속은 조건부 UPDATE라 대상 행에 배타락을 잡고, 이미 귀속된 라인은 조건 불충족으로 재귀속 안 된다. 두 마감이 같은 라인을 노려도 한쪽만 잡는다.

`concurrentCloseCreatesExactlyOneCycle`이 16스레드로 이를 실증한다: 사이클 정확히 1개, 라인 이중 귀속 0. **어느 스레드가 이기든 불변식이 성립**(인터리빙 무관)하는 게 EXCLUDE+조건부 UPDATE의 힘이다.

</details>

<details>
<summary><b>Q3. ⭐ ADR-005는 "유입=FOR SHARE / 마감=FOR UPDATE 락 비대칭"이라 했는데, 이 PR엔 그 락이 없다. 왜?</b></summary>

**모델이 바뀌어서 그 락의 적용 지점이 사라졌다.** 이게 이 PR의 가장 미묘한 지점이라 ADR-018에 정직하게 기록했다.

ADR-005의 락 비대칭은 **eager-assignment** 전제였다: 유입이 "열린 사이클 행"에 즉시 귀속하고 마감이 그 행을 닫으니, 유입과 마감이 **같은 사이클 행을 공유**한다 → 공유 자원이라 상호배제할 락이 필요하다. 그때 얻은 통찰이 "shared 락끼리는 상호배제가 안 되니 유입=FOR SHARE, 마감=FOR UPDATE로 비대칭"이었다(공유 락은 여럿이 동시에, 배타 락은 그들과 배타).

그런데 M4-b/ADR-017이 **deferred-assignment**를 택하면서 **유입이 사이클 행을 아예 안 건드린다**(라인만 NULL로 INSERT). 유입과 마감이 공유하는 사이클 행이 없다 → **상호배제할 대상 자체가 없다** → 락 비대칭이 불필요하다. 대신 직렬화는:
- 이중 마감 = **EXCLUDE**(사이클)
- 라인 귀속 경합 = **조건부 UPDATE 배타락**(라인)
이 맡는다.

ADR-005의 통찰("shared는 상호배제 못 함")은 **여전히 옳다** — 다만 deferred 모델엔 shared 락을 걸 자리가 없어 통찰이 바이팅할 지점이 사라진 것이다. "설계가 진화하면 과거 결정을 다시 판정한다"의 사례고, ADR-018이 그 재판정을 남긴다.

</details>

<details>
<summary><b>Q4. 마감 중에 그 기간 라인이 늦게 도착하면(택배사 콜백 지연) 어떻게 되나? 놓치나?</b></summary>

안 놓친다 — **cutoff 스윕**이 그걸 흡수한다. 이것도 리뷰가 아니라 내가 짜다가 적대적 검증에서 잡은 결함이다(ADR-018 결정1).

처음엔 마감 대상을 `eligible_at >= start AND eligible_at < end` 양변으로 필터했다. 그런데 이러면 **이미 닫힌 과거 창에 늦게 도착한 라인**(`eligible_at`이 그 창 안이지만 마감 후 INSERT)이 문제다: 그 창은 EXCLUDE로 재마감 불가라, 그 라인은 어느 마감에도 안 걸려 **영구 고립**된다.

고쳐서 **하한을 없앴다** — `eligible_at < periodEnd` 만(cutoff). 늦은 라인은 미배정으로 남아 있다가 **다음 마감의 cutoff( 더 큰 end)에 자연히 흡수**된다. `periodStart`는 EXCLUDE가 강제하는 보고 주기 라벨일 뿐 라인 선별 기준이 아니다. `lateArrivalPickedUpByNextClose`가 회귀로 박제한다.

트레이드오프: 늦은 라인은 자기 `eligible_at`이 속한 창이 아니라 **다음 마감 사이클에 귀속**된다(정산이 한 주기 밀린다). 실서비스 정산도 late-arrival을 "다음 주기 이월"로 처리하므로 자연스럽다. 완전한 상태머신 이월(`CARRIED_OVER`)은 후속 스코프.

</details>

<details>
<summary><b>Q5. 왜 셀러별로 루프를 도나? Gemini 말대로 writable CTE 한 문장이 낫지 않나?</b></summary>

배치라 지금은 셀러별 루프가 낫다. 세 이유(ADR-018 defer 근거):

1. **고스트를 못 막는다**: 단일 CTE라도 *다른 `cycle_type`*의 동시 마감은 별개 문장/스냅샷이라 여전히 빈 사이클을 만든다(Q6). 즉 CTE로 바꿔도 `assigned==0` 가드는 따로 필요하다 — CTE가 사는 문제(왕복 수)와 고스트는 층이 다르다.
2. **가독성 = 서사**: 셀러별 `INSERT → 조건부 UPDATE → (가드)`가 ADR-018의 두 겹 방어를 그대로 코드에 드러낸다. 큰 CTE는 이 인과를 한 덩어리로 뭉갠다.
3. **비용이 안 실린다**: 마감은 OLTP가 아니라 배치다. 3N+1 왕복이 주문 API p99에 안 실린다. ADR-004: "측정이 정당화할 때만." 부하 실측이 knee를 보이면 CTE로 진화한다(락 순서 `ORDER BY seller_id`는 CTE에서도 유지 가능).

즉 CTE는 **틀린 게 아니라 아직 이른** 최적화다.

</details>

<details>
<summary><b>Q6. 고스트 사이클이 정확히 뭔가? EXCLUDE가 있는데 왜 빈 사이클이 생기나?</b></summary>

`EXCLUDE`의 키에 **`cycle_type`이 들어간다**는 게 핵심이다: `(seller_id =, cycle_type =, 기간 &&)`. 그래서 **유형이 다르면** 같은 셀러·같은 기간이라도 겹침으로 안 본다(설계 의도 — 한 셀러가 weekly와 express를 병행할 수 있게).

이 틈으로 경합이 열린다: `close(WEEKLY, W)`와 `close(EXPRESS, W)`가 같은 셀러의 미배정 라인 풀을 동시에 마감하면,
1. 둘 다 후보 조회에서 그 라인들을 본다(NULL).
2. 둘 다 사이클 INSERT 성공(유형 달라 EXCLUDE 통과).
3. 먼저 UPDATE한 쪽이 라인을 다 가져가고, 늦은 쪽 UPDATE는 `cycle_id IS NULL`이 이미 거짓이라 **0건**.
4. 늦은 쪽은 **라인 0개 CLOSED 사이클**을 커밋 = 고스트. 그 `(셀러, express, W)`가 EXCLUDE로 봉인돼 재마감 불가 + 조회에 빈 사이클 노출.

`EXCLUDE`는 *기간 겹침*을 막지 *빈 사이클 생성*은 안 막는다. 그래서 `assigned==0 → DELETE` 가드로 봉쇄했다(Q1의 § #1). 8스레드로 8/8 재현해 반례를 테스트에 박고 고쳤다.

왜 DB 트리거로 안 막았나: "빈 CLOSED 금지"는 라인이 사이클 **뒤에** 붙는 순서 때문에 커밋 시점 검사(DEFERRABLE 트리거)가 필요한데, 이 규모엔 과설계다. 사이클은 원장이 아니라 기간 메타고 이 경로가 유일한 writer라(ADR-015) 앱 가드로 충분하다.

</details>

<details>
<summary><b>Q7. 조회(<code>GET /sellers/{id}/settlements</code>)는 왜 REPEATABLE READ인가?</b></summary>

읽기 모델이 **두 번 SELECT**하기 때문이다: (1) 사이클별 순지급 집계, (2) 미배정 pending 합. 기본 격리(READ COMMITTED)면 두 SELECT 사이에 마감이 끼어들어 **사이클엔 이미 귀속됐는데 pending은 옛 값**을 보는 torn read가 난다(예: pending이 방금 사이클로 옮겨갔는데 조회는 양쪽에 다 세거나 다 빠뜨림).

`REPEATABLE READ`면 트랜잭션 첫 쿼리 시점에 스냅샷을 한 번 떠 두 SELECT가 **같은 시점**을 본다 → `totalNetMinor = 마감분 + pending`이 항상 정합. shipping의 `getShipment`가 head/history 두 SELECT에 같은 처방을 쓴 선례(PR #20 리뷰 #2)와 동형이다. 읽기 전용이라 비용도 싸다.

</details>

<details>
<summary><b>Q8. "Σ셀러정산 + 수수료 == 총매출" property는 뭘 증명하나? 마감이 금액을 안 바꾸면 자명하지 않나?</b></summary>

자명해 *보이지만* 그게 함정이라 property로 못박는다. 두 가지를 동시에 증명한다:

1. **마감은 금액을 변조하지 않는다**: 마감 전후 `SUM(amount_minor)` 전체가 불변 — 마감이 `cycle_id`만 건드리고 `amount_minor`엔 손 안 댐을 랜덤 200 시나리오로 확인. UPDATE에 실수로 금액을 건드리는 회귀를 잡는다.
2. **헤드라인 항등식**: `Σ(셀러 순지급) + Σ(수수료) == Σ(매출) == 총액`. 셀러 net = SALE − COMMISSION이니 Σnet + Σcommission = Σsale. 이게 이 프로젝트의 정산 보존 불변식이고, 부호 원장(SALE +, COMMISSION −)이 **선형 SUM으로 보존**됨을 실증한다.

왜 property인가: 예제 하나는 특정 금액·수수료율만 본다. 랜덤 멀티셀러(1~6명) × 랜덤 매출 × 랜덤 수수료율(0~100%)로 폭격해야 "반올림·부호·멀티셀러 fan-out이 얽혀도 보존"을 믿을 수 있다. 돈 항등식은 예제 + property를 함께 두는 게 이 저장소 규율(§2)이다.

</details>
