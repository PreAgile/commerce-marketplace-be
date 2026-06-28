package com.lemong.marketplace.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lemong.marketplace.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 장바구니 불변식이 <b>DB 제약으로</b> 강제됨을 실 Postgres로 검증한다(도메인 1차 방어의 최후 보루). 도메인을 우회해
 * raw INSERT로 들어와도 DB가 막는지를 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CartConstraintsIT {

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("TRUNCATE TABLE cart_item, cart RESTART IDENTITY CASCADE").update();
	}

	private long insertCart() {
		return jdbc.sql("INSERT INTO cart (buyer_id) VALUES (1) RETURNING id").query(Long.class).single();
	}

	private int insertItem(long cartId, long productId, long unitPrice, int quantity) {
		return insertItem(cartId, productId, 10L, unitPrice, quantity);
	}

	private int insertItem(long cartId, long productId, long sellerId, long unitPrice, int quantity) {
		return jdbc.sql("""
				INSERT INTO cart_item (cart_id, product_id, seller_id, unit_price, quantity)
				VALUES (:cart, :product, :seller, :price, :qty)
				""").param("cart", cartId).param("product", productId).param("seller", sellerId)
				.param("price", unitPrice).param("qty", quantity).update();
	}

	@Test
	@DisplayName("정상 항목은 INSERT된다")
	void validItemInserts() {
		assertThat(insertItem(insertCart(), 100L, 3_000L, 2)).isEqualTo(1);
	}

	@Test
	@DisplayName("수량 0은 CHECK 제약으로 거부된다")
	void zeroQuantityRejected() {
		long cart = insertCart();
		assertThatThrownBy(() -> insertItem(cart, 100L, 3_000L, 0)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("음수 단가는 CHECK 제약으로 거부된다")
	void negativeUnitPriceRejected() {
		long cart = insertCart();
		assertThatThrownBy(() -> insertItem(cart, 100L, -1L, 1)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("같은 카트·같은 상품·같은 셀러 두 줄은 UNIQUE로 거부된다(같은 오퍼)")
	void duplicateOfferInCartRejected() {
		long cart = insertCart();
		insertItem(cart, 100L, 10L, 3_000L, 1);
		assertThatThrownBy(() -> insertItem(cart, 100L, 10L, 3_000L, 2))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("같은 상품이라도 셀러가 다르면 별도 줄로 허용된다(멀티셀러)")
	void sameProductDifferentSellerAllowed() {
		long cart = insertCart();
		assertThat(insertItem(cart, 100L, 10L, 3_000L, 1)).isEqualTo(1);
		assertThat(insertItem(cart, 100L, 20L, 3_500L, 1)).isEqualTo(1); // 같은 상품, 다른 셀러
	}

	@Test
	@DisplayName("존재하지 않는 카트에 항목을 달면 FK로 거부된다")
	void itemForMissingCartRejected() {
		assertThatThrownBy(() -> insertItem(999_999L, 100L, 3_000L, 1))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("정의되지 않은 카트 상태는 CHECK 제약으로 거부된다")
	void invalidCartStatusRejected() {
		assertThatThrownBy(() -> jdbc.sql("INSERT INTO cart (buyer_id, status) VALUES (1, 'PURGED')").update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
