package com.lemong.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * M0 스모크 테스트 — 실 Postgres(Testcontainers)에 Flyway 마이그레이션이 적용됐는지 검증.
 *
 * <p>DB를 mock하지 않는다(AGENTS.md): 가짜 repo가 아니라 진짜 DB로 베이스라인이 올라가고,
 * 그 위에서 컨텍스트가 부팅됨을 확인한다. 도메인 코드가 붙기 전 "골격이 실제로 도는가"의 기준선.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchemaSmokeIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("Flyway 베이스라인 마이그레이션이 실 Postgres에 적용된다")
    void flywayBaselineMigrationApplied() {
        Long count = jdbc.sql("SELECT count(*) FROM flyway_baseline_marker")
                .query(Long.class)
                .single();

        assertThat(count).isEqualTo(1L);
    }
}
