package com.orderpilot.application.services.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage8MigrationFileTest {
  @Test
  void migrationAddsReconciliationTablesAndIndexes() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V8__commerce_intelligence_reconciliation.sql"));
    assertThat(sql).contains("CREATE TABLE inventory_movement", "CREATE TABLE reconciliation_case");
    assertThat(sql).contains("tenant_id UUID NOT NULL", "movement_type VARCHAR", "mismatch_quantity NUMERIC");
    assertThat(sql).contains("idx_inventory_movement_tenant_product_location_time", "idx_reconciliation_case_tenant_status_severity", "idx_bot_message_tenant_channel_created");
  }
}
