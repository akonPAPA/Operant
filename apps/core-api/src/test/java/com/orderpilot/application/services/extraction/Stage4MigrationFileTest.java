package com.orderpilot.application.services.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage4MigrationFileTest {
  @Test
  void stage4MigrationDefinesExtractionTables() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V4__ai_assisted_understanding.sql"));
    assertThat(migration).contains("CREATE TABLE extraction_run");
    assertThat(migration).contains("CREATE TABLE extracted_document_text");
    assertThat(migration).contains("CREATE TABLE extraction_result");
    assertThat(migration).contains("CREATE TABLE extracted_field");
    assertThat(migration).contains("CREATE TABLE extracted_line_item");
    assertThat(migration).contains("CREATE TABLE ai_suggestion");
    assertThat(migration).contains("tenant_id UUID NOT NULL");
  }
}