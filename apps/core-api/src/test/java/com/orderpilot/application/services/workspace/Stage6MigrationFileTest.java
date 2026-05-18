package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage6MigrationFileTest {
  @Test
  void migrationAddsWorkspaceTablesAndTenantMarkers() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V6__quote_order_workspace_exception_cockpit.sql"));
    assertThat(sql).contains("CREATE TABLE exception_case", "CREATE TABLE draft_quote", "CREATE TABLE draft_order", "CREATE TABLE approval_decision", "CREATE TABLE operator_action");
    assertThat(sql).contains("tenant_id UUID NOT NULL");
    assertThat(sql).contains("idx_exception_case_tenant_status_created", "idx_draft_quote_tenant_status_created", "idx_draft_order_tenant_status_created", "idx_operator_action_tenant_target");
  }
}
