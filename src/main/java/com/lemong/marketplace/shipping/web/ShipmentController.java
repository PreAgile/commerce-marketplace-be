package com.lemong.marketplace.shipping.web;

import com.lemong.marketplace.shipping.application.ShipmentService;
import com.lemong.marketplace.shipping.application.ShipmentView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 배송 API. 골든 시나리오 S4(배송 상태 전이). 생성은 결제확정 이벤트 구독이라 여기 API가 없다. */
@RestController
@RequestMapping("/shipments")
public class ShipmentController {

	private final ShipmentService shipments;

	public ShipmentController(ShipmentService shipments) {
		this.shipments = shipments;
	}

	@PostMapping("/{id}/events")
	public ShipmentView recordEvent(@PathVariable long id, @Valid @RequestBody ShipmentRequests.RecordEvent req) {
		return shipments.recordTransition(id, req.status(), req.occurredAt());
	}

	@GetMapping("/{id}")
	public ShipmentView getShipment(@PathVariable long id) {
		return shipments.getShipment(id);
	}
}
