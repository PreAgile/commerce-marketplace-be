package com.lemong.marketplace.settlement.application;

import com.lemong.marketplace.settlement.domain.CycleType;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 사이클 마감 배치(S5 후반). 배송완료 소비가 {@code cycle_id=NULL}로 쌓아둔 미배정 라인(ADR-017)을
 * {@code [periodStart, periodEnd)} 창의 셀러별 사이클에 귀속시킨다. 원장은 부호 있는 append-only라
 * 마감은 금액을 만들지 않고 {@code cycle_id}만 채운다 — 셀러 순지급은 여전히 SUM 도출이다.
 *
 * <p>
 * 이중 마감·동시 마감 방어는 코드 if가 아니라 두 DB 구조가 한다(ADR-018):
 * <ul>
 * <li><b>이중 집계 금지</b> = {@code ex_cycle_no_overlap} EXCLUDE. 같은 셀러·유형의 창을 두 번째로
 * 마감하려는 INSERT는 겹침으로 거부된다(재실행/동시 마감 모두 최대 1건만 커밋).
 * <li><b>라인 귀속 배타성</b> = {@code WHERE cycle_id IS NULL} 조건부 UPDATE. 조건부 UPDATE가
 * 대상 행에 배타락을 잡고, 이미 귀속된 라인은 조건 불충족으로 재귀속되지 않는다 — 두 마감이 같은 라인을 노려도 한쪽만 잡는다.
 * </ul>
 *
 * <p>
 * deferred 모델이라(유입은 사이클 행을 건드리지 않는다) ADR-005의 유입/마감 락 비대칭은 이 경로엔 필요 없다 — 유입-마감이
 * 같은 행을 공유하지 않기 때문. 셀러 사이클 INSERT는 {@code seller_id} 오름차순으로 돌려 EXCLUDE 인덱스 상의 락
 * 획득 순서를 고정한다(데드락 예방).
 *
 * <p>
 * 라인 선별은 <b>cutoff 스윕</b>이다 — {@code eligible_at < periodEnd} 인 미배정 라인을 전부 잡는다
 * (하한 없음). 하한을 두면 이미 닫힌 과거 창에 늦게 도착한 라인(택배사 콜백 지연)이 어느 마감에도 안 걸려 영구 고립된다.
 * cutoff 스윕은 늦은 라인을 다음 마감이 자연히 흡수한다(손실 없음). {@code periodStart}는 EXCLUDE가 강제하는
 * 보고 주기의 시작일 뿐, 라인 선별 기준이 아니다.
 */
@Service
@Transactional
public class SettlementCloseService {

	private final JdbcClient jdbc;

	public SettlementCloseService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 한 셀러 사이클 마감 결과. netMinor는 귀속 라인 SUM(부호 원장이라 그대로 순지급). */
	public record CycleSummary(long sellerId, long cycleId, int lineCount, long netMinor) {
	}

	/**
	 * {@code eligible_at < periodEnd} 인 미배정 라인을 셀러별
	 * {@code [periodStart, periodEnd)} 사이클로 마감한다(cutoff 스윕). 마감 뒤 늦게 도착한 라인은 미배정으로
	 * 남아 다음 마감이 흡수한다(손실·이중집계 없음).
	 *
	 * @return 이번 마감으로 라인이 귀속된 셀러별 요약(대상 라인이 없는 셀러는 제외)
	 * @throws IllegalArgumentException
	 *             창이 반열림이 아닐 때(end <= start)
	 * @throws org.springframework.dao.DataIntegrityViolationException
	 *             이미 마감된 창을 다시 마감하려 할 때(EXCLUDE) — 배치 전체가 롤백돼 부분 마감이 남지 않는다
	 */
	public List<CycleSummary> run(CycleType type, OffsetDateTime periodStart, OffsetDateTime periodEnd) {
		if (!periodEnd.isAfter(periodStart)) {
			throw new IllegalArgumentException(
					"정산 창은 반열림 [start, end) 여야 한다: start=" + periodStart + ", end=" + periodEnd);
		}

		List<Long> sellers = jdbc.sql("""
				SELECT DISTINCT seller_id FROM settlement_line
				WHERE cycle_id IS NULL AND eligible_at < :end
				ORDER BY seller_id
				""").param("end", periodEnd).query(Long.class).list();

		List<CycleSummary> summaries = new ArrayList<>();
		for (long sellerId : sellers) {
			long cycleId = jdbc.sql("""
					INSERT INTO settlement_cycle (seller_id, cycle_type, period_start, period_end, status)
					VALUES (:s, :type, :start, :end, 'CLOSED')
					RETURNING cycle_id
					""").param("s", sellerId).param("type", type.dbValue()).param("start", periodStart)
					.param("end", periodEnd).query(Long.class).single();

			int assigned = jdbc.sql("""
					UPDATE settlement_line SET cycle_id = :cid
					WHERE seller_id = :s AND cycle_id IS NULL AND eligible_at < :end
					""").param("cid", cycleId).param("s", sellerId).param("end", periodEnd).update();

			if (assigned == 0) {
				// 후보 조회와 UPDATE 사이(TOCTOU)에 다른 유형·창 마감이 이 셀러의 라인을 선점하면 UPDATE가 0건이다.
				// 방금 만든 사이클을 지워 라인 0개짜리 CLOSED "고스트"가 (seller,type,기간)을 영구 봉인하는 걸 막는다.
				// settlement_cycle은 원장이 아니라 기간 메타라 같은 TX 내 DELETE가 안전(잔액 원장 UPDATE 금지와 무관).
				jdbc.sql("DELETE FROM settlement_cycle WHERE cycle_id = :cid").param("cid", cycleId).update();
				continue;
			}

			long net = jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM settlement_line WHERE cycle_id = :cid")
					.param("cid", cycleId).query(Long.class).single();
			summaries.add(new CycleSummary(sellerId, cycleId, assigned, net));
		}
		return summaries;
	}
}
