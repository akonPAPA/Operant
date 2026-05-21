package com.orderpilot.application.services.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage13MigrationFileTest {
  @Test
  void migrationAddsSecretMetadataDiagnosticsAndVerificationColumns() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V21__connector_security_provider_onboarding.sql"));
    assertThat(sql).contains("secret_reference_id", "secret_last_updated_at", "webhook_verification_mode", "verification_status", "duration_ms", "error_category");
    assertThat(sql).contains("DISABLED_FOR_LOCAL_DEV", "SHARED_SECRET", "SIGNATURE_HEADER", "PROVIDER_SPECIFIC");
    assertThat(sql).doesNotContain("raw_secret", "raw_token", "password");
  }
}
