package com.orderpilot.application.services.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage7MigrationFileTest {
  @Test
  void migrationAddsBotRuntimeLiteTablesAndTenantMarkers() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V7__bot_runtime_lite.sql"));
    assertThat(sql).contains("CREATE TABLE bot_conversation", "CREATE TABLE bot_message", "CREATE TABLE bot_rfq_request", "CREATE TABLE bot_handoff");
    assertThat(sql).contains("tenant_id UUID NOT NULL", "requires_human_review BOOLEAN NOT NULL");
    assertThat(sql).contains("idx_bot_conversation_tenant_status", "idx_bot_rfq_request_tenant_status", "idx_bot_handoff_tenant_status");
  }
}
