package com.lemong.marketplace.cart.infra;

import com.lemong.marketplace.cart.domain.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

/** Cart 애그리거트 영속 포트. Spring Data JPA가 구현 제공. */
public interface CartRepository extends JpaRepository<Cart, Long> {
}
