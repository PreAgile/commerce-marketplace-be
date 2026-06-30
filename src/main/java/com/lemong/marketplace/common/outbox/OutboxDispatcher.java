package com.lemong.marketplace.common.outbox;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미발행 outbox 행 한 건을 핸들러에 디스패치하고 발행 표시한다. 한 건 = 한 트랜잭션이라 핸들러 작업·발행 마킹이 원자적이다.
 *
 * <p>
 * {@code FOR UPDATE}로 행을 잠그고 {@code published_at IS NULL}을 재확인한다 — 여러 릴레이 인스턴스가
 * 같은 행을 동시에 집어도 한쪽만 처리하고 나머지는 빈 결과로 빠진다(중복 발행 방지). 그래도 at-least-once라 핸들러는
 * 멱등이어야 한다.
 */
@Component
public class OutboxDispatcher {

	private final JdbcClient jdbc;
	private final List<OutboxEventHandler> handlers;

	public OutboxDispatcher(JdbcClient jdbc, List<OutboxEventHandler> handlers) {
		this.jdbc = jdbc;
		this.handlers = handlers;
	}

	@Transactional
	public void dispatch(long outboxId) {
		var locked = jdbc.sql("""
				SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload
				FROM outbox
				WHERE id = :id AND published_at IS NULL
				FOR UPDATE
				""").param("id", outboxId)
				.query((rs, n) -> new OutboxMessage(rs.getLong("id"), rs.getString("aggregate_type"),
						rs.getLong("aggregate_id"), rs.getString("event_type"), rs.getString("payload")))
				.optional();
		if (locked.isEmpty()) {
			return; // 이미 다른 릴레이가 발행함
		}
		OutboxMessage message = locked.get();
		for (OutboxEventHandler handler : handlers) {
			if (handler.supports(message.eventType())) {
				handler.handle(message);
			}
		}
		jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = :id").param("id", outboxId).update();
	}
}
