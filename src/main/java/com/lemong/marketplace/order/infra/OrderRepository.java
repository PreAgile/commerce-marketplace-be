package com.lemong.marketplace.order.infra;

import com.lemong.marketplace.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

/** Order 애그리거트 영속 포트. Spring Data JPA가 구현 제공. */
public interface OrderRepository extends JpaRepository<Order, Long> {
}
