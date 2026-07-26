package com.orderpilot.domain.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.orderpilot.domain.control.BackupArtifact.AvailableMetadata;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit proof for the canonical PostgreSQL tool / Flyway schema version invariant. The Java validation
 * must be identical to the V68 {@code ck_backup_artifact_pg_versions} / {@code ck_backup_artifact_schema
 * _version} CHECK constraints so stored provenance has exactly one representation and raw tool banners
 * are rejected before they can reach the database.
 */
class BackupArtifactTest {
  private static final String HANDLE = "ba_000000000000000000000001";
  private static final String STORAGE_KEY = "lifecycle/backup/op_abc/attempt-1/token-1/artifact.dump.enc";
  private static final String SHA =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Test
  void canonicalRegexConstantsMatchTheMigrationContract() {
    assertThat(BackupArtifact.CANONICAL_TOOL_VERSION_REGEX)
        .isEqualTo("^[0-9]{1,3}(\\.[0-9]{1,3}){0,2}$");
    assertThat(BackupArtifact.CANONICAL_SCHEMA_VERSION_REGEX)
        .isEqualTo("^V[0-9]{1,6}(\\.[0-9]{1,6}){0,3}$");
  }

  @Test
  void markAvailableAcceptsCanonicalToolAndSchemaVersions() {
    BackupArtifact artifact = staged();
    artifact.markAvailable(metadata("16.4", "16.4", "16.4", "V68"), Instant.now());

    assertThat(artifact.getState()).isEqualTo(BackupArtifactState.AVAILABLE);
    assertThat(artifact.isAuthoritative()).isTrue();
  }

  @Test
  void markAvailableRejectsRawPostgresToolBanner() {
    BackupArtifact artifact = staged();
    assertThatThrownBy(() -> artifact.markAvailable(
        metadata("PostgreSQL 16", "16.4", "16.4", "V68"), Instant.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("postgresServerVersion_INVALID");
  }

  @Test
  void markAvailableRejectsSchemaVersionWithoutCanonicalPrefix() {
    BackupArtifact artifact = staged();
    assertThatThrownBy(() -> artifact.markAvailable(
        metadata("16.4", "16.4", "16.4", "68"), Instant.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("schemaVersion_INVALID");
  }

  private static BackupArtifact staged() {
    return BackupArtifact.staged(
        HANDLE, mock(LifecycleOperation.class), "POSTGRES_CUSTOM", STORAGE_KEY, 1, 1L, Instant.now());
  }

  private static AvailableMetadata metadata(
      String postgresServerVersion, String pgDumpVersion, String pgRestoreVersion, String schemaVersion) {
    return new AvailableMetadata(
        "AES-256-GCM",
        "v1",
        "backup-key-2026-07",
        postgresServerVersion,
        pgDumpVersion,
        pgRestoreVersion,
        schemaVersion,
        128L,
        SHA,
        true,
        12);
  }
}
