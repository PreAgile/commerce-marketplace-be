/**
 * 공통(Common) — 컨텍스트 횡단 인프라.
 *
 * <p>
 * outbox(발행 마킹·재발행 스윕·poison 격리), 멱등키, 시계(Clock) 추상화 등 도메인 간 공유 기반. 도메인 규칙은 각
 * 컨텍스트가 소유하며, 여기엔 비즈니스 불변식을 두지 않는다. AGENTS.md 참조.
 */
package com.lemong.marketplace.common;
