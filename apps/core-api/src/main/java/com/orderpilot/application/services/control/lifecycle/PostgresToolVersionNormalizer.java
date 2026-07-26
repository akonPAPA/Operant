package com.orderpilot.application.services.control.lifecycle;

import com.orderpilot.domain.control.BackupArtifact;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The authoritative boundary normalizer for PostgreSQL tool/server provenance reported by the (external,
 * untrusted) backup executor on a bounded completion report.
 *
 * <p>Motivation: nothing forces the executor to have already reduced {@code pg_dump --version} /
 * {@code SELECT version()} output to a canonical string. The executor could report either a canonical
 * value ({@code 16.4}) or a raw CLI banner ({@code pg_dump (PostgreSQL) 16.4}). This normalizer reduces
 * an enumerated, closed set of recognised banner shapes to the one canonical representation enforced by
 * {@link BackupArtifact#CANONICAL_TOOL_VERSION_REGEX} and the V68 {@code ck_backup_artifact_pg_versions}
 * CHECK constraint, and fail-closes on anything ambiguous or unknown.
 *
 * <p><b>Semantic provenance is preserved per field.</b> A generic normalizer that accepted any of the
 * {@code PostgreSQL} / {@code pg_dump} / {@code pg_restore} / {@code psql} banners interchangeably for
 * every field would silently let a {@code psql} banner masquerade as the server version, or a bare server
 * banner masquerade as the {@code pg_dump} version. Instead each field is normalized against the ONE
 * banner shape its tool actually emits:
 *
 * <ul>
 *   <li>{@link ExpectedPostgresTool#POSTGRES_SERVER} accepts canonical or {@code PostgreSQL <v>} only.
 *   <li>{@link ExpectedPostgresTool#PG_DUMP} accepts canonical or {@code pg_dump (PostgreSQL) <v>} only.
 *   <li>{@link ExpectedPostgresTool#PG_RESTORE} accepts canonical or {@code pg_restore (PostgreSQL) <v>}
 *       only.
 * </ul>
 *
 * <p>Each banner shape may be followed by a single bounded, paren-free distro note
 * ({@code  (Ubuntu 16.4-1.pgdg22.04+1)}). Everything else — a foreign tool's banner, {@code v16},
 * {@code 16.}, empty output, an unrecognised tool name, an ambiguous multi-version string, a banner
 * carrying no parseable version, multiline/control noise, or oversized input — raises
 * {@link IllegalArgumentException}, which the control lifecycle boundary maps to a bounded
 * {@code 400 INVALID_BACKUP_ARTIFACT_REPORT} (never the raw banner). The extracted token is always
 * re-checked against the canonical contract before it is returned, so this normalizer can never emit a
 * non-canonical value into the domain / DB layer.
 */
public final class PostgresToolVersionNormalizer {
  private static final int MAX_RAW_LENGTH = 120;

  private static final Pattern CANONICAL = Pattern.compile(BackupArtifact.CANONICAL_TOOL_VERSION_REGEX);

  // Reused canonical MAJOR[.MINOR[.PATCH]] token and the single optional bounded, paren-free distro note.
  private static final String VERSION_GROUP = "([0-9]{1,3}(?:\\.[0-9]{1,3}){0,2})";
  private static final String OPTIONAL_DISTRO_NOTE = "(?: \\([^()]{1,60}\\))?";

  /**
   * The semantic identity of a reported PostgreSQL tool/server version. Each constant carries the bounded
   * field name used only for the fail-closed reason code (never the raw value) and the single banner shape
   * that its underlying tool actually emits. A field is normalized ONLY against its own tool's banner, so
   * one tool's banner can never be accepted in another tool's field.
   */
  public enum ExpectedPostgresTool {
    POSTGRES_SERVER("postgresServerVersion", "PostgreSQL"),
    PG_DUMP("pgDumpVersion", "pg_dump (PostgreSQL)"),
    PG_RESTORE("pgRestoreVersion", "pg_restore (PostgreSQL)");

    private final String field;
    private final Pattern banner;

    ExpectedPostgresTool(String field, String bannerPrefix) {
      this.field = field;
      // ^<literal tool banner prefix> <canonical version>[ (<bounded distro note>)]$
      this.banner = Pattern.compile(
          "^" + Pattern.quote(bannerPrefix) + " " + VERSION_GROUP + OPTIONAL_DISTRO_NOTE + "$");
    }

    String field() {
      return field;
    }

    Pattern banner() {
      return banner;
    }
  }

  private PostgresToolVersionNormalizer() {}

  /**
   * Normalises one reported tool/server version to its canonical representation, preserving semantic
   * provenance: the value is accepted only if it is already canonical or matches the banner shape emitted
   * by {@code tool} itself.
   *
   * @param tool the expected tool identity for the field being normalized (drives both the accepted banner
   *     shape and the bounded reason code)
   * @param raw the executor-reported value (canonical or a recognised banner for {@code tool})
   * @return the canonical {@code MAJOR[.MINOR[.PATCH]]} version
   * @throws IllegalArgumentException if the value is blank, oversized, a foreign tool's banner, or not a
   *     recognised canonical / banner form for {@code tool}
   */
  public static String normalize(ExpectedPostgresTool tool, String raw) {
    Objects.requireNonNull(tool, "tool");
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(tool.field() + "_REQUIRED");
    }
    String trimmed = raw.strip();
    if (trimmed.length() > MAX_RAW_LENGTH) {
      throw new IllegalArgumentException(tool.field() + "_INVALID");
    }
    if (CANONICAL.matcher(trimmed).matches()) {
      return trimmed;
    }
    Matcher matcher = tool.banner().matcher(trimmed);
    if (matcher.matches()) {
      String canonical = matcher.group(1);
      // Defence in depth: the extracted token must itself satisfy the canonical contract before it is
      // ever handed to the domain/DB layer, so this normalizer can never emit a non-canonical value.
      if (CANONICAL.matcher(canonical).matches()) {
        return canonical;
      }
    }
    throw new IllegalArgumentException(tool.field() + "_INVALID");
  }
}
