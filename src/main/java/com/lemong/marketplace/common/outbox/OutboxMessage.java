package com.lemong.marketplace.common.outbox;

/** 릴레이가 핸들러에게 넘기는 미발행 outbox 행의 읽기 모델. payload는 jsonb의 텍스트 표현. */
public record OutboxMessage(long id, String aggregateType, long aggregateId, String eventType, String payload) {
}
