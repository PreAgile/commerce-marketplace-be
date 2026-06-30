package com.lemong.marketplace.shipping.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lemong.marketplace.common.outbox.OutboxEventHandler;
import com.lemong.marketplace.common.outbox.OutboxMessage;
import org.springframework.stereotype.Component;

/**
 * PaymentConfirmed 이벤트를 받아 셀러별 배송 생성을 트리거한다. source_event_id는
 * {@code payment:{paymentId}} — 결제확정은 결제당 1건이라 이 값이 불변·고유하고, 셀러와 합쳐
 * (source_event_id, seller_id) 멱등 키가 된다.
 */
@Component
class PaymentConfirmedHandler implements OutboxEventHandler {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final ShipmentService shipments;

	PaymentConfirmedHandler(ShipmentService shipments) {
		this.shipments = shipments;
	}

	@Override
	public boolean supports(String eventType) {
		return "PaymentConfirmed".equals(eventType);
	}

	@Override
	public void handle(OutboxMessage message) {
		try {
			JsonNode payload = JSON.readTree(message.payload());
			long orderId = payload.get("orderId").asLong();
			long paymentId = payload.get("paymentId").asLong();
			shipments.createForPaymentConfirmed(orderId, "payment:" + paymentId);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("PaymentConfirmed payload 파싱 실패: outbox id=" + message.id(), e);
		}
	}
}
