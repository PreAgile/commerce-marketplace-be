package com.lemong.marketplace.settlement.web;

import com.lemong.marketplace.settlement.application.SettlementCloseService;
import com.lemong.marketplace.settlement.application.SettlementCloseService.CycleSummary;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 정산 운영 API. 골든 시나리오 S5(정산 마감 배치). 운영자가 닫힌 창을 지정해 마감을 트리거한다. */
@RestController
@RequestMapping("/admin/settlements")
public class AdminSettlementController {

	private final SettlementCloseService close;

	public AdminSettlementController(SettlementCloseService close) {
		this.close = close;
	}

	public record RunCloseResponse(List<CycleSummary> cycles) {
	}

	@PostMapping("/run")
	public RunCloseResponse run(@Valid @RequestBody SettlementRequests.RunClose req) {
		return new RunCloseResponse(close.run(req.cycleType(), req.periodStart(), req.periodEnd()));
	}
}
