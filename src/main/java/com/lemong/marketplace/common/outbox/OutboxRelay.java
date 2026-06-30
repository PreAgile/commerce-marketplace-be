package com.lemong.marketplace.common.outbox;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미발행 outbox 행을 주기적으로 폴링해 디스패처에 넘기는 릴레이(ADR-002의 사후 발행 단계). 한 건 실패가 배치를 막지 않도록
 * 건마다 격리해 디스패처를 호출한다(디스패처가 건별 트랜잭션).
 *
 * <p>
 * {@code outbox.relay.enabled=true}일 때만 빈으로 뜬다 — 테스트는 이 폴링을 끄고 디스패처를 직접 호출해
 * 결정적으로 검증한다(백그라운드 폴링이 끼어들어 상태를 바꾸는 비결정성 차단).
 */
@Component
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true")
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private final JdbcClient jdbc;
	private final OutboxDispatcher dispatcher;

	public OutboxRelay(JdbcClient jdbc, OutboxDispatcher dispatcher) {
		this.jdbc = jdbc;
		this.dispatcher = dispatcher;
	}

	@Scheduled(fixedDelayString = "${outbox.relay.interval-ms:1000}")
	public void relay() {
		List<Long> pending = jdbc.sql("SELECT id FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 100")
				.query(Long.class).list();
		for (long id : pending) {
			try {
				dispatcher.dispatch(id);
			} catch (RuntimeException e) {
				// 격리: 한 건의 핸들러 실패가 나머지 발행을 막지 않게. 해당 행은 다음 폴링에서 재시도된다.
				log.warn("outbox relay failed for id={}, will retry", id, e);
			}
		}
	}
}
