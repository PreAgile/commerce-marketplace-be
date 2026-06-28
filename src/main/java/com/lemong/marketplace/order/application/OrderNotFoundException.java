package com.lemong.marketplace.order.application;

import com.lemong.marketplace.common.error.ResourceNotFoundException;

/** 존재하지 않는 주문을 조회할 때. GlobalExceptionHandler가 404로 매핑한다. */
public class OrderNotFoundException extends ResourceNotFoundException {

	public OrderNotFoundException(long orderId) {
		super("order not found: " + orderId);
	}
}
