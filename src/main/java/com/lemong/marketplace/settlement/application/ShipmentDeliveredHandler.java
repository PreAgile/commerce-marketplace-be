package com.lemong.marketplace.settlement.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lemong.marketplace.common.outbox.OutboxEventHandler;
import com.lemong.marketplace.common.outbox.OutboxMessage;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/**
 * ShipmentDelivered 이벤트를 받아 셀러 정산 라인 생성을 트리거한다. payload의
 * shipmentId/orderId/sellerId/deliveredAt로 {@link SettlementService}가
 * SALE·COMMISSION을 멱등하게 append한다. 디스패처 트랜잭션에 합류하므로 실패 시 발행 마킹까지 롤백돼
 * 재시도된다(at-least-once) — 그래서 핸들러는 멱등이어야 한다.
 */
@Component
class ShipmentDeliveredHandler implements OutboxEventHandler {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final SettlementService settlements;

	ShipmentDeliveredHandler(SettlementService settlements) {
		this.settlements = settlements;
	}

	@Override
	public boolean supports(String eventType) {
		return "ShipmentDelivered".equals(eventType);
	}

	@Override
	public void handle(OutboxMessage message) {
		try {
			JsonNode p = JSON.readTree(message.payload());
			settlements.recordSaleForDelivery(p.get("shipmentId").asLong(), p.get("orderId").asLong(),
					p.get("sellerId").asLong(), OffsetDateTime.parse(p.get("deliveredAt").asText()));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("ShipmentDelivered payload 파싱 실패: outbox id=" + message.id(), e);
		}
	}
}
