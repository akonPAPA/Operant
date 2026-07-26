package com.orderpilot.application.services.control.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
  // postgresServerVersion: the bounded `SHOW server_version` producer contract ONLY, i.e. canonical
  // MAJOR[.MINOR[.PATCH]] or that value plus a single bounded package suffix. There is deliberately no
  // "PostgreSQL <v>" (SELECT version()) banner acceptance and no generic banner parser.
  //
  // Exact accepted server forms (documented contract):
  //   16                              -> 16
  //   16.4                            -> 16.4
  //   16.4.1                          -> 16.4.1
  //   16.4 (Ubuntu 16.4-1.pgdg22.04+1)-> 16.4
  //   16.4 (Debian 16.4-1.pgdg120+1)  -> 16.4
  // ---------------------------------------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({
      "16,                                16",
      "16.4,                              16.4",
      "16.4.1,                            16.4.1",
      "16.4 (Ubuntu 16.4-1.pgdg22.04+1),  16.4",
      "16.4 (Debian 16.4-1.pgdg120+1),    16.4",
      "'  16.4  ',                        16.4"
  })
  void serverVersionAcceptsCanonicalAndShowServerVersionSuffix(String raw, String expected) {
    String normalized = PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.POSTGRES_SERVER, raw);
    assertThat(normalized).isEqualTo(expected);
    assertThat(CANONICAL.matcher(normalized).matches()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "PostgreSQL 16.4",                // SELECT version() prefix / server banner is NOT accepted here
      "PostgreSQL 16",                  // bare server banner is not the SHOW server_version form
      "PostgreSQL 16.4 (Ubuntu 16.4-1.pgdg22.04+1)", // full SELECT version() prefix + suffix
      "pg_dump (PostgreSQL) 16.4",      // pg_dump banner in the server field
      "pg_restore (PostgreSQL) 16.4",   // pg_restore banner in the server field
      "psql (PostgreSQL) 16.4",         // psql banner in the server field
      "postgres (PostgreSQL) 16.4",     // postgres binary banner
      "16.4 17.2",                      // ambiguous / multiple-version
      "16.4 (Ubuntu 16.4) 17.2",        // trailing extra version after the suffix
      "PostgreSQL",                     // no version token
      "server version 16.4",            // arbitrary text containing a version
      "v16"                             // not canonical, not a server producer form
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
      "pg_dump (PostgreSQL) 16.4 (Ubuntu 16.4-1.pgdg22.04+1), 16.4",
      // Full closed-allowlist coverage in one note: letters, digits, dot, underscore, plus, tilde,
      // colon, hyphen, and single spaces between non-empty tokens.
      "pg_dump (PostgreSQL) 16.4 (Ubuntu 16.4-1.pgdg22.04+1 build_a~b c:d), 16.4"
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
      "pg_dump (PostgreSQL) 16.4; DROP TABLE backup_artifact"  // injection-shaped banner (note outside)
  })
  void pgDumpRejectsForeignToolsAndMalformedBanners(String raw) {
    assertThatThrownBy(() -> PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.PG_DUMP, raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pgDumpVersion_INVALID");
  }

  /**
   * Closed distro-note charset proof: characters OUTSIDE the ASCII package-version allowlist, placed
   * INSIDE the parenthesized note itself, must be rejected. The prior suite only checked a newline
   * outside the note, which is insufficient — an attacker controls the note contents.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "pg_dump (PostgreSQL) 16.4 (Ubuntu 16.4;rm -rf)",        // semicolon inside the note
      "pg_dump (PostgreSQL) 16.4 (Ubuntu \"16.4\")",           // double quote inside the note
      "pg_dump (PostgreSQL) 16.4 (Ubuntu '16.4')",             // single quote inside the note
      "pg_dump (PostgreSQL) 16.4 (Ubuntu\\16.4)",              // backslash inside the note
      "pg_dump (PostgreSQL) 16.4 (Ubuntu {16.4})",             // brace inside the note
      "pg_dump (PostgreSQL) 16.4 (Ubuntu [16.4])",             // bracket inside the note
      "pg_dump (PostgreSQL) 16.4 (Ubuntu (16.4))",             // nested parenthesis inside the note
      "pg_dump (PostgreSQL) 16.4 (Ubuntu  16.4)",              // doubled space between note tokens
      "pg_dump (PostgreSQL) 16.4 ( Ubuntu 16.4)",              // leading space inside the note
      "pg_dump (PostgreSQL) 16.4 (Ubuntu 16.4 )",              // trailing space inside the note
      "pg_dump (PostgreSQL) 16.4 (Ubuntu=16.4)",               // equals sign inside the note
      "pg_dump (PostgreSQL) 16.4 (Ubuntu,16.4)",               // comma inside the note
      "pg_dump (PostgreSQL) 16.4 (Ubuntu\t16.4)",              // tab (control) inside the note
      "pg_dump (PostgreSQL) 16.4 (Ubuntu\n16.4)",              // LF (control) inside the note
      "pg_dump (PostgreSQL) 16.4 (Ubuntu\r16.4)",              // CR (control) inside the note
  })
  void pgDumpRejectsCharactersOutsideClosedDistroNoteAllowlist(String raw) {
    assertThatThrownBy(() -> PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.PG_DUMP, raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pgDumpVersion_INVALID");
  }

  @Test
  void pgDumpRejectsNulAndUnicodeSeparatorsInsideDistroNote() {
    // NUL and the Unicode line/paragraph separators are built via char casts rather than embedded
    // literals so the source file carries no raw control bytes. Each sits INSIDE the parenthesized note.
    for (char c : new char[] {(char) 0x0000, (char) 0x2028, (char) 0x2029, (char) 0x0085}) {
      String raw = "pg_dump (PostgreSQL) 16.4 (Ubuntu" + c + "16.4)";
      assertThatThrownBy(() -> PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.PG_DUMP, raw))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("pgDumpVersion_INVALID");
    }
  }

  @Test
  void pgDumpRejectsMultilineControlInput() {
    // An embedded newline must not slip a second (attacker-chosen) line past the anchored full-string
    // match. The control/line-separator scrub rejects it before any pattern is applied.
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
    Throwable thrown = catchThrowable(
        () -> PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.PG_DUMP, oversized));
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pgDumpVersion_INVALID");
    // The raw value must never be echoed back in the fail-closed reason code.
    assertThat(thrown.getMessage()).isEqualTo("pgDumpVersion_INVALID").doesNotContain("aaaa");
  }

  @Test
  void exactlyMaxRawLengthIsAcceptedWhenOtherwiseValid() {
    // A pg_dump banner whose ORIGINAL length is exactly MAX_RAW_LENGTH (120) and otherwise valid is
    // accepted; the bound is inclusive. Prefix "pg_dump (PostgreSQL) 16.4 (" (27) + note + ")" (1) = 120,
    // so the closed-allowlist note token is 92 chars.
    String raw = "pg_dump (PostgreSQL) 16.4 (" + "a".repeat(92) + ")";
    assertThat(raw.length()).isEqualTo(120);
    assertThat(PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.PG_DUMP, raw)).isEqualTo("16.4");
  }

  @Test
  void oneOverMaxRawLengthIsRejected() {
    // Exactly MAX_RAW_LENGTH + 1 (121) is rejected purely on the length bound.
    String raw = "pg_dump (PostgreSQL) 16.4 (" + "a".repeat(93) + ")";
    assertThat(raw.length()).isEqualTo(121);
    assertThatThrownBy(() -> PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.PG_DUMP, raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pgDumpVersion_INVALID");
  }

  @Test
  void validCanonicalTokenSurroundedByOverMaxPaddingIsRejected() {
    // A single otherwise-valid canonical token wrapped in whitespace padding whose ORIGINAL length
    // exceeds MAX_RAW_LENGTH must be rejected: the bound is enforced on the raw String BEFORE strip(),
    // so whitespace padding can never bypass MAX_RAW_LENGTH even though the stripped token is valid.
    String raw = " ".repeat(60) + "16.4" + " ".repeat(60); // 124 chars, not blank, strips to "16.4"
    assertThat(raw.length()).isGreaterThan(120);
    assertThat(raw.strip()).isEqualTo("16.4");
    assertThatThrownBy(() -> PostgresToolVersionNormalizer.normalize(ExpectedPostgresTool.POSTGRES_SERVER, raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("postgresServerVersion_INVALID");
  }

  @Test
  void normalizedBannersFeedMarkAvailableWithoutRejection() {
    // End-to-end constraint proof: each field's banner, normalized against its own tool identity, is
    // accepted by the domain's own canonical validation in markAvailable (which mirrors the V68 CHECK).
    // No weakening of the domain contract, and provenance is preserved per field.
    String pg = PostgresToolVersionNormalizer.normalize(
        ExpectedPostgresTool.POSTGRES_SERVER, "16.4 (Ubuntu 16.4-1.pgdg22.04+1)");
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
