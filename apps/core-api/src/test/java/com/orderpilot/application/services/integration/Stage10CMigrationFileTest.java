package com.orderpilot.application.services.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage10CMigrationFileTest {
  @Test
  void migrationAddsChangeRequestAndInternalOutboxTablesOnly() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V10__change_request_transactional_outbox.sql"));
    assertThat(sql).contains("CREATE TABLE change_request", "CREATE TABLE outbox_event");
    assertThat(sql).contains("tenant_id UUID NOT NULL", "target_system VARCHAR(40) NOT NULL", "request_payload_json JSONB NOT NULL");
    assertThat(sql).contains("validation_status VARCHAR(40) NOT NULL", "approval_status VARCHAR(40) NOT NULL", "execution_status VARCHAR(40) NOT NULL");
    assertThat(sql).contains("aggregate_type VARCHAR(80) NOT NULL", "aggregate_id UUID NOT NULL", "event_type VARCHAR(120) NOT NULL");
    assertThat(sql).doesNotContain("CREATE TABLE connector", "secret", "credential", "api_key", "DROP TABLE", "ALTER TABLE");
    assertThat(sql).contains("ck_change_request_stage10c_no_external_execution", "SKIPPED_EXTERNAL_DISABLED", "PUBLISHED_INTERNAL_ONLY");
  }
}