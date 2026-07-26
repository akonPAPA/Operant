package com.orderpilot.application.services.control.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.control.lifecycle.PostgresToolVersionNormalizer.ExpectedPostgresTool;
import com.orderpilot.domain.control.BackupArtifact;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Locally-runnable proof for the provenance-preserving boundary normalizer (GAP 2/4). The normalizer must
 * reduce ONLY the banner shape a given tool actually emits to the one canonical representation the domain
 * ({@link BackupArtifact#CANONICAL_TOOL_VERSION_REGEX}) and the V68 {@code ck_backup_artifact_pg_versions}
 * CHECK enforce, and fail-close on anything ambiguous, foreign, or malformed. Critically, semantic
 * provenance is preserved: a {@code psql}/{@code pg_restore}/{@code postgres} banner must NOT be accepted
 * in the {@code pg_dump} field, and a tool banner must NOT be accepted in the server-version field.
 */
class PostgresToolVersionNormalizerTest {
  private static final Pattern CANONICAL =
      Pattern.compile(BackupArtifact.CANONICAL_TOOL_VERSION_REGEX);

  // ---------------------------------------------------------------------------------------------------
  // postgresServerVersion: canonical or "PostgreSQL <v>" only.
  // ---------------------------------------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({
      "16,                                       16",
      "16.4,                                     16.4",
      "16.4.1,                                   16.4.1",
      "PostgreSQL 16.4,                          16.4",
      "PostgreSQL 16,                            16",
      "PostgreSQL 16.4 (Debian 16.4-1.pgdg120+1), 16.4",
      "'  16.4  ',                               16.4"
  })
  void serverVersionAcceptsCanonicalAndServerBanner(String raw, String expected) {
    String normalized = PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.POSTGRES_SERVER, raw);
    assertThat(normalized).isEqualTo(expected);
    assertThat(CANONICAL.matcher(normalized).matches()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "pg_dump (PostgreSQL) 16.4",      // pg_dump banner in the server field
      "pg_restore (PostgreSQL) 16.4",   // pg_restore banner in the server field
      "psql (PostgreSQL) 16.4",         // psql banner in the server field
      "postgres (PostgreSQL) 16.4",     // postgres binary banner
      "PostgreSQL 16.4 17.2",           // ambiguous / multiple-version
      "PostgreSQL",                     // no version token
      "server version 16.4",            // arbitrary text containing a version
      "v16"                             // not canonical, not a server banner
  })
  void serverVersionRejectsForeignBannersAndAmbiguousInput(String raw) {
    assertThatThrownBy(
            () -> PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.POSTGRES_SERVER, raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("postgresServerVersion_INVALID");
  }

  // ---------------------------------------------------------------------------------------------------
  // pgDumpVersion: canonical or "pg_dump (PostgreSQL) <v>" only.
  // ---------------------------------------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({
      "16,                                                    16",
      "16.4,                                                  16.4",
      "16.4.1,                                                16.4.1",
      "pg_dump (PostgreSQL) 16.4,                             16.4",
      "pg_dump (PostgreSQL) 16.4 (Ubuntu 16.4-1.pgdg22.04+1), 16.4"
  })
  void pgDumpAcceptsCanonicalAndPgDumpBanner(String raw, String expected) {
    String normalized = PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.PG_DUMP, raw);
    assertThat(normalized).isEqualTo(expected);
    assertThat(CANONICAL.matcher(normalized).matches()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "psql (PostgreSQL) 16.4",         // psql banner in the pg_dump field
      "pg_restore (PostgreSQL) 16.4",   // pg_restore banner in the pg_dump field
      "postgres (PostgreSQL) 16.4",     // postgres binary banner
      "PostgreSQL 16.4",                // bare server banner is not the pg_dump banner
      "pgdump (PostgreSQL) 16.4",       // unknown tool identity (missing underscore)
      "pg_dump 16.4",                   // banner missing the (PostgreSQL) qualifier
      "pg_dump (PostgreSQL) sixteen",   // non-numeric version
      "pg_dump (PostgreSQL) 16.4 17.2", // ambiguous / multiple-version
      "pg_dump (PostgreSQL) 16.4 extra trailing noise",       // unbounded trailing text
      "pg_dump (PostgreSQL) 16.4; DROP TABLE backup_artifact" // injection-shaped banner
  })
  void pgDumpRejectsForeignToolsAndMalformedBanners(String raw) {
    assertThatThrownBy(() -> PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.PG_DUMP, raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pgDumpVersion_INVALID");
  }

  @Test
  void pgDumpRejectsMultilineControlInput() {
    // An embedded newline must not slip a second (attacker-chosen) line past the anchored full-string
    // match. Matcher.matches() requires the whole input to match, so the control character is rejected.
    assertThatThrownBy(() -> PostgresToolVersionNormalizer.normalize(
            ExpectedPostgresTool.PG_DUMP, "pg_dump (PostgreSQL) 16.4\n17.2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pgDumpVersion_INVALID");
    assertThatThrownBy(() -> PostgresToolVersionNormalizer.normalize(
            ExpectedPostgresTool.PG_DUMP, "pg_dump (PostgreSQL)\t16.4"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pgDumpVersion_INVALID");
  }

  // ---------------------------------------------------------------------------------------------------
  // pgRestoreVersion: canonical or "pg_restore (PostgreSQL) <v>" only.
  // ---------------------------------------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({
      "16.4,                                                     16.4",
      "pg_restore (PostgreSQL) 16.4,                             16.4",
      "pg_restore (PostgreSQL) 16.4 (Ubuntu 16.4-1.pgdg22.04+1), 16.4"
  })
  void pgRestoreAcceptsCanonicalAndPgRestoreBanner(String raw, String expected) {
    String normalized = PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.PG_RESTORE, raw);
    assertThat(normalized).isEqualTo(expected);
    assertThat(CANONICAL.matcher(normalized).matches()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "pg_dump (PostgreSQL) 16.4",      // pg_dump banner in the pg_restore field
      "psql (PostgreSQL) 16.4",         // psql banner in the pg_restore field
      "PostgreSQL 16.4"                 // bare server banner is not the pg_restore banner
  })
  void pgRestoreRejectsForeignBanners(String raw) {
    assertThatThrownBy(
            () -> PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.PG_RESTORE, raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pgRestoreVersion_INVALID");
  }

  // ---------------------------------------------------------------------------------------------------
  // Cross-field contract: bounds, blanks, and the end-to-end domain acceptance.
  // ---------------------------------------------------------------------------------------------------

  @Test
  void failsClosedOnBlankWithBoundedReasonThatDoesNotEchoRawValue() {
    assertThatThrownBy(
            () -> PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.PG_DUMP, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pgDumpVersion_REQUIRED");
    assertThatThrownBy(
            () -> PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.PG_DUMP, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pgDumpVersion_REQUIRED");
  }

  @Test
  void oversizedBannerIsRejectedWithoutEchoingValue() {
    String oversized = "pg_dump (PostgreSQL) 16.4 (" + "a".repeat(200) + ")";
    assertThatThrownBy(
            () -> PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.PG_DUMP, oversized))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pgDumpVersion_INVALID");
  }

  @Test
  void normalizedBannersFeedMarkAvailableWithoutRejection() {
    // End-to-end constraint proof: each field's banner, normalized against its own tool identity, is
    // accepted by the domain's own canonical validation in markAvailable (which mirrors the V68 CHECK).
    // No weakening of the domain contract, and provenance is preserved per field.
    String pg = PostgresToolVersionNormalizer.normalize(
        ExpectedPostgresTool.POSTGRES_SERVER, "PostgreSQL 16.4");
    String dump = PostgresToolVersionNormalizer.normalize(
        ExpectedPostgresTool.PG_DUMP, "pg_dump (PostgreSQL) 16.4 (Ubuntu 16.4-1.pgdg22.04+1)");
    String restore = PostgresToolVersionNormalizer.normalize(
        ExpectedPostgresTool.PG_RESTORE, "pg_restore (PostgreSQL) 16.4");

    BackupArtifact artifact = BackupArtifact.staged(
        "ba_000000000000000000000001",
        org.mockito.Mockito.mock(com.orderpilot.domain.control.LifecycleOperation.class),
        "POSTGRES_CUSTOM",
        "lifecycle/backup/op_abc/attempt-1/token-1/artifact.dump.enc",
        1,
        1L,
        java.time.Instant.now());
    artifact.markAvailable(
        new BackupArtifact.AvailableMetadata(
            "AES-256-GCM", "v1", "backup-key-2026-07", pg, dump, restore, "V68", 128L,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", true, 12),
        java.time.Instant.now());

    assertThat(artifact.getPostgresServerVersion()).isEqualTo("16.4");
    assertThat(artifact.getPgDumpVersion()).isEqualTo("16.4");
    assertThat(artifact.getPgRestoreVersion()).isEqualTo("16.4");
  }
}
