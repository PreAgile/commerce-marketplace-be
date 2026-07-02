package com.lemong.marketplace.settlement.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 정산 주기 유형. DB(settlement_cycle.cycle_type CHECK)와 API JSON은 소문자('weekly')로,
 * 코드는 대문자 상수로 다룬다 — {@link #dbValue()}가 그 경계를 번역한다.
 */
public enum CycleType {
	DAILY, WEEKLY, MONTHLY, EXPRESS;

	@JsonValue
	public String dbValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static CycleType from(String value) {
		return valueOf(value.trim().toUpperCase(Locale.ROOT));
	}
}
