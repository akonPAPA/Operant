package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage5MigrationFileTest {
  @Test
  void migrationAddsValidationTablesAndTenantMarkers() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V5__validation_substitution_pricing_intelligence.sql"));
    assertThat(sql).contains("CREATE TABLE validation_run", "CREATE TABLE validation_issue", "CREATE TABLE substitute_candidate", "CREATE TABLE approval_requirement");
    assertThat(sql).contains("tenant_id UUID NOT NULL");
    assertThat(sql).contains("idx_validation_run_tenant_result", "idx_substitute_candidate_tenant_product", "idx_approval_requirement_tenant_status");
  }
}
