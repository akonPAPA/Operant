package com.orderpilot.application.services.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage10BMigrationFileTest {
  @Test
  void migrationAddsMockOnlyShadowModeAndCorrectionTables() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V9__pilot_shadow_mode_metrics.sql"));
    assertThat(sql).contains("CREATE TABLE shadow_run", "CREATE TABLE human_correction");
    assertThat(sql).contains("tenant_id UUID NOT NULL");
    assertThat(sql).contains("provider_mode VARCHAR(30) NOT NULL");
    assertThat(sql).contains("CHECK (provider_mode = 'MOCK_ONLY')");
    assertThat(sql).contains("idx_shadow_run_tenant_source_status", "idx_human_correction_tenant_shadow_run");
  }
}
