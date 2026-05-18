package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*; import org.junit.jupiter.api.Test;

class Stage3MigrationFileTest {
  @Test void stage3MigrationDefinesIntakeTables() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V3__omnichannel_intake.sql"));
    assertThat(migration).contains("CREATE TABLE inbound_document");
    assertThat(migration).contains("CREATE TABLE channel_message");
    assertThat(migration).contains("CREATE TABLE processing_job");
    assertThat(migration).contains("CREATE TABLE webhook_event");
    assertThat(migration).contains("tenant_id UUID NOT NULL");
  }
}