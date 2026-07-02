# 시각 가이드 (visual guides)

정산 한 줄기의 핵심 개념을 그림으로 풀어, 백지에서 흐름을 설명할 수 있게 정리한 self-contained HTML 문서. 브라우저로 바로 연다.

| 파일 | 무엇을 |
|---|---|
| [settlement-close-guide.html](./settlement-close-guide.html) | **정산 마감(M4-c)** — 부호 원장·봉투(cycle) 개념, 배송완료→적재→마감→조회 흐름, 테이블 상태 변화, 동시성 방어(EXCLUDE·조건부 UPDATE·고스트 가드) |
| [refund-partial-guide.html](./refund-partial-guide.html) | **부분환불(M5, 설계 지도)** — 환불을 음수 줄로 되돌리는 원리, payment·settlement를 잇는 흐름, cutoff 흡수, 누적환불 저장 방식 갈림길 |

설계 배경은 [`../design/implementation-spec.md`](../design/implementation-spec.md)·[`../ai-collaboration/decision-log.md`](../ai-collaboration/decision-log.md), PR별 리뷰는 [`../pr-reviews/`](../pr-reviews/) 참조.
