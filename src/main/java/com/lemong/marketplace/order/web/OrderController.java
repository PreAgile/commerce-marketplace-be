package com.lemong.marketplace.order.web;

import com.lemong.marketplace.order.application.OrderView;
import com.lemong.marketplace.order.application.PlaceOrderService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 주문 API. 골든 시나리오 S1 후반(장바구니 → 주문). */
@RestController
@RequestMapping("/orders")
public class OrderController {

	private final PlaceOrderService orders;

	public OrderController(PlaceOrderService orders) {
		this.orders = orders;
	}

	public record CreateOrderResponse(long orderId) {
	}

	@PostMapping
	public ResponseEntity<CreateOrderResponse> place(@Valid @RequestBody OrderRequests.PlaceOrder req) {
		long orderId = orders.place(req.cartId());
		return ResponseEntity.created(URI.create("/orders/" + orderId)).body(new CreateOrderResponse(orderId));
	}

	@GetMapping("/{id}")
	public OrderView getOrder(@PathVariable long id) {
		return orders.getOrder(id);
	}
}
