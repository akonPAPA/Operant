package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage2MigrationFileTest {
  @Test
  void stage2MigrationDefinesRequiredTables() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V2__data_foundation_import_mirror.sql"));

    assertThat(migration).contains("CREATE TABLE customer_account");
    assertThat(migration).contains("CREATE TABLE product");
    assertThat(migration).contains("CREATE TABLE inventory_snapshot");
    assertThat(migration).contains("CREATE TABLE import_job");
    assertThat(migration).contains("CREATE TABLE import_staging_row");
    assertThat(migration).contains("tenant_id UUID NOT NULL");
  }
}