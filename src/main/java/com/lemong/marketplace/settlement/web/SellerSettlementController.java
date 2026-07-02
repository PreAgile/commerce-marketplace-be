package com.lemong.marketplace.settlement.web;

import com.lemong.marketplace.settlement.application.SellerSettlementView;
import com.lemong.marketplace.settlement.application.SettlementQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 셀러 정산 조회 API. 골든 시나리오 S5 후속 — 마감된 사이클별 순지급과 미배정 잔량을 읽는다. */
@RestController
@RequestMapping("/sellers")
public class SellerSettlementController {

	private final SettlementQueryService query;

	public SellerSettlementController(SettlementQueryService query) {
		this.query = query;
	}

	@GetMapping("/{id}/settlements")
	public SellerSettlementView settlements(@PathVariable long id) {
		return query.forSeller(id);
	}
}
