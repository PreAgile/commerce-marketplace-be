/**
 * 배송(Shipping) Bounded Context.
 *
 * <p>
 * 결제확정 이벤트를 구독해 셀러별 배송 단위를 만든다. 상태 전이는 상태머신으로 가드하고, 이벤트는 at-least-once 가정 하에
 * 멱등(source_event_id) 처리한다. 경계 규칙은 AGENTS.md 참조.
 */
package com.lemong.marketplace.shipping;
