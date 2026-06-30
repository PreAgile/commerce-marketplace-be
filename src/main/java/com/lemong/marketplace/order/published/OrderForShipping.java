package com.lemong.marketplace.order.published;

import java.util.List;

/**
 * 배송 컨텍스트가 주문의 셀러 구성을 읽기 위한 published 포트(order가 제공, shipping이 소비 — 단방향). 배송은 주문
 * 내부 엔티티·테이블을 보지 않고 이 계약으로만 협력한다(BC 경계, ArchitectureTest가 강제). M3-a는 셀러별 배송 N건
 * 생성이 목적이라 distinct seller_id 목록만 노출한다.
 */
public interface OrderForShipping {

	List<Long> sellerIdsForOrder(long orderId);
}
