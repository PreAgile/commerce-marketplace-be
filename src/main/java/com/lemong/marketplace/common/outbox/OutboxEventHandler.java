package com.lemong.marketplace.common.outbox;

/**
 * outbox 이벤트 소비자가 구현하는 in-process 핸들러(모듈러 모놀리스 — 외부 브로커 없이 같은 JVM에서 디스패치,
 * ADR-004). 디스패처가 event_type으로 매칭해 호출한다. 호출은 디스패처의 트랜잭션에 합류하므로 핸들러 작업과 발행 마킹이
 * 원자적이다 — 핸들러가 던지면 마킹도 롤백돼 다음 폴링에서 재시도된다(at-least-once). 따라서 핸들러는 멱등이어야 한다.
 */
public interface OutboxEventHandler {

	boolean supports(String eventType);

	void handle(OutboxMessage message);
}
