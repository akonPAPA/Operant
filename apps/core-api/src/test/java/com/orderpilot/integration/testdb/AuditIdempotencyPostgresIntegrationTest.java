package com.orderpilot.integration.testdb;

import static com.orderpilot.support.DatabaseIntegrationTestBase.CLEAN;
import static com.orderpilot.support.DatabaseIntegrationTestBase.TENANTS;
import static com.orderpilot.support.DatabaseIntegrationTestBase.USERS_ROLES;
import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static com.orderpilot.support.TestTenantFixtures.TENANT_B;
import static com.orderpilot.support.TestUserFixtures.USER_A;
import static com.orderpilot.support.TestUserFixtures.USER_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@Sql(scripts = {CLEAN, TENANTS, USERS_ROLES})
class AuditIdempotencyPostgresIntegrationTest extends DatabaseIntegrationTestBase {
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void auditQueriesStayTenantScoped() {
    jdbcTemplate.update(
        "INSERT INTO audit_event (tenant_id, actor_id, action, entity_type, entity_id, metadata, occurred_at) VALUES (?, ?, 'QUOTE_REVIEWED', 'QUOTE', 'quote-a', '{\"safe\":true}', '2026-06-01T12:00:00Z')",
        TENANT_A,
        USER_A);
    jdbcTemplate.update(
        "INSERT INTO audit_event (tenant_id, actor_id, action, entity_type, entity_id, metadata, occurred_at) VALUES (?, ?, 'QUOTE_REVIEWED', 'QUOTE', 'quote-b', '{\"safe\":true}', '2026-06-01T12:01:00Z')",
        TENANT_B,
        USER_B);

    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(TENANT_A))
        .hasSize(1)
        .extracting("entityId")
        .containsExactly("quote-a");
    assertThat(auditEventRepository.findByTenantIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(TENANT_A, "QUOTE", "quote-b"))
        .isEmpty();
  }

  @Test
  void idempotencyKeysAreUniquePerTenantOnly() {
    insertIdempotency(TENANT_A, "same-key");
    insertIdempotency(TENANT_B, "same-key");

    assertThatThrownBy(() -> insertIdempotency(TENANT_A, "same-key"))
        .isInstanceOf(DataAccessException.class);
  }

  private void insertIdempotency(java.util.UUID tenantId, String keyHash) {
    jdbcTemplate.update(
        "INSERT INTO idempotency_key (tenant_id, key_hash, request_fingerprint, expires_at) VALUES (?, ?, 'fixture-fingerprint', now() + interval '1 day')",
        tenantId,
        keyHash);
  }
}
