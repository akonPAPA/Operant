package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage2GapClosureMigrationFileTest {
  @Test
  void gapClosureMigrationDefinesRemainingStage2TablesAndIndexes() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V22__stage2_data_foundation_gap_closure.sql"));

    assertThat(migration).contains("CREATE TABLE customer_contact");
    assertThat(migration).contains("CREATE TABLE vehicle_make");
    assertThat(migration).contains("CREATE TABLE vehicle_model");
    assertThat(migration).contains("CREATE TABLE vehicle_year");
    assertThat(migration).contains("CREATE TABLE vehicle_configuration");
    assertThat(migration).contains("CREATE TABLE import_validation_issue");
    assertThat(migration).contains("idx_discount_rule_tenant_segment_product");
    assertThat(migration).contains("idx_margin_rule_tenant_product");
  }
}