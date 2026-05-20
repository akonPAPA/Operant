package com.orderpilot.application.services.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage12MigrationFileTest {
  @Test
  void migrationAddsTenantScopedChannelAndIntegrationFoundation() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V20__universal_channel_integration_foundation.sql"));
    assertThat(sql).contains("CREATE TABLE channel_connection", "CREATE TABLE integration_connection", "CREATE TABLE inbound_channel_event", "CREATE TABLE connector_sync_event");
    assertThat(sql).contains("tenant_id UUID NOT NULL", "READ_ONLY", "secret_ref");
    assertThat(sql).contains("TELEGRAM", "WHATSAPP", "WECHAT", "ONE_C", "GENERIC_DATABASE", "SAP");
    assertThat(sql).contains("uq_inbound_channel_event_external_id", "tenant_id, provider_type, external_event_id");
    assertThat(sql).doesNotContain("api_key", "password", "raw_token");
  }
}
