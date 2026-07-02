package com.lemong.marketplace.settlement.application;

import com.lemong.marketplace.settlement.application.SellerSettlementView.CycleNet;
import com.lemong.marketplace.settlement.domain.CycleType;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 정산 조회 읽기 모델. 마감 사이클별 순지급과 미배정(pending) 순액을 REPEATABLE READ 한 스냅샷으로 읽어 사이클
 * 집계와 pending이 서로 다른 시점을 보고 어긋나는 일을 막는다.
 */
@Service
public class SettlementQueryService {

	private final JdbcClient jdbc;

	public SettlementQueryService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public SellerSettlementView forSeller(long sellerId) {
		List<CycleNet> cycles = jdbc.sql("""
				SELECT c.cycle_id, c.cycle_type, c.period_start, c.period_end, c.status,
				       COUNT(l.line_id)                  AS line_count,
				       COALESCE(SUM(l.amount_minor), 0)  AS net
				FROM settlement_cycle c
				LEFT JOIN settlement_line l ON l.cycle_id = c.cycle_id
				WHERE c.seller_id = :s
				GROUP BY c.cycle_id, c.cycle_type, c.period_start, c.period_end, c.status
				ORDER BY c.period_start, c.cycle_id
				""").param("s", sellerId)
				.query((rs, n) -> new CycleNet(rs.getLong("cycle_id"), CycleType.from(rs.getString("cycle_type")),
						rs.getObject("period_start", java.time.OffsetDateTime.class),
						rs.getObject("period_end", java.time.OffsetDateTime.class), rs.getString("status"),
						rs.getInt("line_count"), rs.getLong("net")))
				.list();

		long pending = jdbc.sql("""
				SELECT COALESCE(SUM(amount_minor), 0) FROM settlement_line
				WHERE seller_id = :s AND cycle_id IS NULL
				""").param("s", sellerId).query(Long.class).single();

		long closed = cycles.stream().mapToLong(CycleNet::netMinor).sum();
		return new SellerSettlementView(sellerId, cycles, pending, closed + pending);
	}
}
