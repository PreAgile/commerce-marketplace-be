package com.lemong.marketplace.order.infra;

import com.lemong.marketplace.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
