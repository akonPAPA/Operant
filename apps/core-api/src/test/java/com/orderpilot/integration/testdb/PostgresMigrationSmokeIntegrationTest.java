package com.orderpilot.integration.testdb;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.support.DatabaseIntegrationTestBase;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PostgresMigrationSmokeIntegrationTest extends DatabaseIntegrationTestBase {
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void migrationsCreateExpectedPostgresSchema() {
    Integer successCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM flyway_schema_history WHERE success = true",
        Integer.class);

    assertThat(successCount).isNotNull().isGreaterThan(0);
    assertThat(tableNames("tenant", "audit_event", "idempotency_key", "product", "draft_quote", "quote_conversion_attempt"))
        .containsExactly(
            "tenant",
            "audit_event",
            "idempotency_key",
            "product",
            "draft_quote",
            "quote_conversion_attempt");
  }

  private List<String> tableNames(String... names) {
    return Arrays.stream(names)
        .map(name -> jdbcTemplate.queryForObject("SELECT to_regclass(?)::text", String.class, name))
        .toList();
  }
}
