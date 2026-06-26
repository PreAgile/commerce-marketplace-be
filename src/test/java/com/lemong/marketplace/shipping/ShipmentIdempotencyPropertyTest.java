package com.lemong.marketplace.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lemong.marketplace.TestcontainersConfiguration;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 배송 생성 멱등을 property로 검증한다: DB는 같은 {@code (source_event_id, seller_id)} 의 두 번째 생성은
 * 항상 거부하고, 같은 이벤트라도 셀러가 다르면 항상 허용해야 한다(멀티셀러).
 *
 * <p>예제 테스트(ShipmentConstraintsIT)가 못 보는 셀러 id 공간을 랜덤 수백 시나리오로 폭격한다(AGENTS.md).
 */
@JqwikSpringSupport
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ShipmentIdempotencyPropertyTest {

    @Autowired
    JdbcClient jdbc;

    // 각 try가 고유 이벤트 id를 써 서로 격리되므로 별도 cleanup이 불필요하다.
    private final AtomicLong seq = new AtomicLong();

    @Property(tries = 200)
    void duplicateEventSellerRejected_distinctSellerAccepted(
            @ForAll @LongRange(min = 1, max = 1_000) long sellerA,
            @ForAll boolean sameSeller,
            @ForAll @LongRange(min = 1_001, max = 2_000) long sellerB) {

        String eventId = "prop-" + seq.incrementAndGet();   // try마다 고유 이벤트

        // 첫 배송은 항상 성공
        assertThat(insert(eventId, sellerA)).isEqualTo(1);

        if (sameSeller) {
            // 같은 (이벤트, 셀러) 재수신 → 멱등으로 거부
            assertThatThrownBy(() -> insert(eventId, sellerA))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } else {
            // 같은 이벤트, 다른 셀러(범위가 겹치지 않음) → 새 배송 허용
            assertThat(insert(eventId, sellerB)).isEqualTo(1);
        }
    }

    private int insert(String eventId, long sellerId) {
        return jdbc.sql("""
                        INSERT INTO shipment (order_id, seller_id, source_event_id, status)
                        VALUES (1, :seller, :evid, 'READY')
                        """)
                .param("seller", sellerId)
                .param("evid", eventId)
                .update();
    }
}
