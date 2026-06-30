package com.lemong.marketplace.shipping.application;

import com.lemong.marketplace.common.error.ResourceNotFoundException;

/** 존재하지 않는 배송에 접근할 때. GlobalExceptionHandler가 404로 매핑한다. */
public class ShipmentNotFoundException extends ResourceNotFoundException {

	public ShipmentNotFoundException(long shipmentId) {
		super("shipment not found: " + shipmentId);
	}
}
