package com.lemong.marketplace.cart.infra;

import com.lemong.marketplace.cart.domain.Cart;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Cart 애그리거트 영속 포트. Spring Data JPA가 구현 제공. */
public interface CartRepository extends JpaRepository<Cart, Long> {

	/**
	 * 쓰기(담기)용 로드. 루트 version을 강제 증가시켜 같은 카트로의 동시 쓰기를 직렬화한다. 자식(CartItem) 수량만 바뀌어도
	 * 루트 version이 올라가므로 lost update가 충돌로 드러난다. 단순 조회는 락 없는 기본 {@link #findById}를
	 * 쓴다(읽기 트랜잭션에서 version UPDATE 금지).
	 */
	@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
	@Query("select c from Cart c where c.id = :id")
	Optional<Cart> findByIdForUpdate(@Param("id") long id);
}
