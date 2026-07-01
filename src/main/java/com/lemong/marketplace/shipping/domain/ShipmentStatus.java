package com.lemong.marketplace.shipping.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 배송 상태와 합법 전이를 정의하는 상태머신. 전이 화이트리스트를 enum 구조로 박제해, 역전이·단계 건너뛰기·자가 전이·종료 상태에서의
 * 전이를 코드 if문이 아니라 자료구조로 거부한다.
 *
 * <p>
 * DB(V4)는 상태 문자열의 도메인만 CHECK로 막고, <i>전이의 합법성</i>은 여기서만 판정한다(DDL 주석의 설계 결정).
 * 해피패스는 선형 전진이며 어느 진행 단계에서든 FAILED로 빠질 수 있다. RETURNED는 반품 기능(로드맵) 전까지 도달 경로가
 * 없다.
 */
public enum ShipmentStatus {

	READY, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, FAILED, RETURNED;

	private static final Map<ShipmentStatus, Set<ShipmentStatus>> ALLOWED = new EnumMap<>(ShipmentStatus.class);

	static {
		ALLOWED.put(READY, EnumSet.of(PICKED_UP, FAILED));
		ALLOWED.put(PICKED_UP, EnumSet.of(IN_TRANSIT, FAILED));
		ALLOWED.put(IN_TRANSIT, EnumSet.of(OUT_FOR_DELIVERY, FAILED));
		ALLOWED.put(OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, FAILED));
		ALLOWED.put(DELIVERED, EnumSet.noneOf(ShipmentStatus.class));
		ALLOWED.put(FAILED, EnumSet.noneOf(ShipmentStatus.class));
		ALLOWED.put(RETURNED, EnumSet.noneOf(ShipmentStatus.class));
	}

	public boolean canTransitionTo(ShipmentStatus target) {
		return ALLOWED.get(this).contains(target);
	}

	public boolean isTerminal() {
		return ALLOWED.get(this).isEmpty();
	}
}
