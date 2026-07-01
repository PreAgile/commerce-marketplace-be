package com.lemong.marketplace.order.published;

/**
 * 정산 컨텍스트가 주문의 셀러별 매출 몫을 읽기 위한 published 포트(order 제공, settlement 소비 — 단방향,
 * ArchUnit이 경계 강제). 배송완료 이벤트엔 금액이 없어, 정산은 이 계약으로 셀러 몫을 조회해 SALE 금액을 정한다.
 */
public interface OrderForSettlement {

	/** 주문에서 해당 셀러 몫 매출 합(Σ line_amount). 주문·셀러 라인이 없으면 0. */
	long sellerAmountForOrder(long orderId, long sellerId);
}
