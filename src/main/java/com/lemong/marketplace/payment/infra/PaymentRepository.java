package com.lemong.marketplace.payment.infra;

import com.lemong.marketplace.payment.domain.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Payment 애그리거트 영속 포트. Spring Data JPA가 구현 제공. */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
