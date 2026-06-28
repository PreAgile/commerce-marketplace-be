package com.lemong.marketplace.cart.web;

import com.lemong.marketplace.cart.application.CartService;
import com.lemong.marketplace.cart.application.CartView;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 장바구니 API. 골든 시나리오 S1의 "담기" 단계. */
@RestController
@RequestMapping("/carts")
public class CartController {

	private final CartService cartService;

	public CartController(CartService cartService) {
		this.cartService = cartService;
	}

	public record CreateCartResponse(long cartId) {
	}

	@PostMapping
	public ResponseEntity<CreateCartResponse> createCart(@Valid @RequestBody CartRequests.CreateCart req) {
		long cartId = cartService.createCart(req.buyerId());
		return ResponseEntity.created(URI.create("/carts/" + cartId)).body(new CreateCartResponse(cartId));
	}

	@PostMapping("/{id}/items")
	public CartView addItem(@PathVariable long id, @Valid @RequestBody CartRequests.AddItem req) {
		return cartService.addItem(id, req.productId(), req.sellerId(), req.unitPrice(), req.quantity());
	}

	@GetMapping("/{id}")
	public CartView getCart(@PathVariable long id) {
		return cartService.getCart(id);
	}
}
