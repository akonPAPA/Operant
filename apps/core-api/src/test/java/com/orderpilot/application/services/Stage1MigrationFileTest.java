package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage1MigrationFileTest {
  @Test
  void platformFoundationMigrationDefinesIdentityAuditAndIdempotencyTables() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__platform_foundation.sql"));

    assertThat(migration).contains("CREATE TABLE tenant");
    assertThat(migration).contains("CREATE TABLE user_account");
    assertThat(migration).contains("CREATE TABLE role");
    assertThat(migration).contains("CREATE TABLE permission");
    assertThat(migration).contains("CREATE TABLE user_role");
    assertThat(migration).contains("CREATE TABLE audit_event");
    assertThat(migration).contains("CREATE TABLE idempotency_key");
    assertThat(migration).contains("tenant_id UUID NOT NULL");
    assertThat(migration).contains("uq_idempotency_tenant_key");
  }
}
