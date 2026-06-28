package com.lemong.marketplace.common.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 도메인 변경과 같은 트랜잭션에서 outbox에 이벤트를 적재한다(ADR-002). 호출자의 @Transactional에 합류하므로 도메인
 * 쓰기와 이벤트 적재가 원자적으로 함께 커밋/롤백된다.
 *
 * <p>
 * 중복 적재(같은 aggregate+event_type)는 uq_outbox_event가 거부한다 — 호출자는 그 23505를 멱등 신호로
 * 다룬다(예: 이미 PAID면 confirm은 append를 시도하지 않음).
 */
@Component
public class OutboxAppender {

	private final JdbcClient jdbc;

	public OutboxAppender(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void append(String aggregateType, long aggregateId, String eventType, String payloadJson) {
		jdbc.sql("""
				INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload)
				VALUES (:type, :id, :event, CAST(:payload AS jsonb))
				""").param("type", aggregateType).param("id", aggregateId).param("event", eventType)
				.param("payload", payloadJson).update();
	}
}
