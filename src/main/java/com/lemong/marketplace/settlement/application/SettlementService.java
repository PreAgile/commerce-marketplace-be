package com.lemong.marketplace.settlement.application;

import com.lemong.marketplace.order.published.OrderForSettlement;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배송완료(ShipmentDelivered)를 소비해 셀러별 정산 원장을 append한다(S5). 한 배송건 = 한 셀러 몫이라 매출
 * SALE(+)과 수수료 COMMISSION(−) 두 줄을 쌓는다.
 *
 * <p>
 * 멱등은 {@code (source_event_id, entry_type, seller_id)} UNIQUE + ON CONFLICT DO
 * NOTHING으로 강제한다 (payment/shipping 선례). at-least-once 재전달을 no-op으로 흡수하므로 핸들러가 두
 * 번 불려도 라인은 한 벌만 남는다. 매출 금액은 order.published 포트로, 수수료율은 commission_policy로 조회하고
 * 없으면 기본율을 쓴다.
 */
@Service
@Transactional
public class SettlementService {

	// commission_policy에 셀러 항목이 없을 때의 기본 수수료율. bps(1000 = 10%).
	static final int DEFAULT_RATE_BPS = 1000;

	private final OrderForSettlement orders;
	private final JdbcClient jdbc;

	public SettlementService(OrderForSettlement orders, JdbcClient jdbc) {
		this.orders = orders;
		this.jdbc = jdbc;
	}

	public void recordSaleForDelivery(long shipmentId, long orderId, long sellerId, OffsetDateTime deliveredAt) {
		long amount = orders.sellerAmountForOrder(orderId, sellerId);
		if (amount <= 0) {
			return; // 셀러 몫이 없으면(주문이 아직 안 보이거나 0원) 원장에 쓰지 않는다. at-least-once 안전.
		}
		int rateBps = jdbc.sql("SELECT rate_bps FROM commission_policy WHERE seller_id = :s").param("s", sellerId)
				.query(Integer.class).optional().orElse(DEFAULT_RATE_BPS);
		long commission = commissionMinor(amount, rateBps);

		String sourceEventId = "shipment:" + shipmentId;
		appendLine(sellerId, "SALE", amount, sourceEventId, shipmentId, deliveredAt);
		if (commission > 0) {
			// rate 0%나 반올림으로 0이 되면 COMMISSION 라인을 만들지 않는다(ck_settline_sign은 COMMISSION<0
			// 요구).
			appendLine(sellerId, "COMMISSION", -commission, sourceEventId, shipmentId, deliveredAt);
		}
	}

	// floor 반올림. rate 상한이 100%(10000bps)라 0 <= commission <= amount 가 보장돼 셀러 순지급이
	// 음수로 안 간다.
	// 오버플로는 multiplyExact가 fail-loud(Money 선례).
	public static long commissionMinor(long amount, int rateBps) {
		return Math.multiplyExact(amount, (long) rateBps) / 10_000;
	}

	private void appendLine(long sellerId, String entryType, long amountMinor, String sourceEventId, long sourceId,
			OffsetDateTime eligibleAt) {
		jdbc.sql("""
				INSERT INTO settlement_line
				    (seller_id, entry_type, source_type, source_id, source_event_id, amount_minor, eligible_at)
				VALUES (:seller, :type, 'ORDER_ITEM', :sid, :evid, :amt, :at)
				ON CONFLICT (source_event_id, entry_type, seller_id) DO NOTHING
				""").param("seller", sellerId).param("type", entryType).param("sid", sourceId)
				.param("evid", sourceEventId).param("amt", amountMinor).param("at", eligibleAt).update();
	}
}
