package com.orderpilot.application.services.control.lifecycle;

import com.orderpilot.domain.control.BackupArtifact;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The authoritative boundary normalizer for PostgreSQL tool/server provenance reported by the (external,
 * untrusted) backup executor on a bounded completion report.
 *
 * <p>Motivation: nothing forces the executor to have already reduced its version-producer output to a
 * canonical string. The executor may report either a canonical value ({@code 16.4}) or the one bounded
 * producer form defined for that field. This normalizer reduces an enumerated, closed set of recognised
 * shapes to the one canonical representation enforced by
 * {@link BackupArtifact#CANONICAL_TOOL_VERSION_REGEX} and the V68 {@code ck_backup_artifact_pg_versions}
 * CHECK constraint, and fail-closes on anything ambiguous or unknown.
 *
 * <p><b>Semantic provenance is preserved per field.</b> A generic normalizer that accepted any of the
 * {@code PostgreSQL} / {@code pg_dump} / {@code pg_restore} / {@code psql} / {@code postgres} banners
 * interchangeably for every field would silently let a {@code psql} banner masquerade as the server
 * version, or a bare server banner masquerade as the {@code pg_dump} version. Instead each field is
 * normalized against the ONE bounded producer shape defined for it:
 *
 * <ul>
 *   <li>{@link ExpectedPostgresTool#POSTGRES_SERVER} accepts canonical {@code MAJOR[.MINOR[.PATCH]]} or
 *       that value plus a single bounded package suffix, i.e. the output of {@code SHOW server_version}
 *       ({@code 16.4} or {@code 16.4 (Ubuntu 16.4-1.pgdg22.04+1)}). It deliberately does NOT accept a
 *       tool-name-prefixed banner: {@code SELECT version()} output ({@code PostgreSQL 16.4 (…) on
 *       x86_64-… compiled by …}), a {@code postgres}/{@code psql}/{@code pg_dump}/{@code pg_restore}
 *       binary banner, and any other prefixed form are rejected. This is a narrow bounded contract, not a
 *       generic PostgreSQL banner parser.
 *   <li>{@link ExpectedPostgresTool#PG_DUMP} accepts canonical or {@code pg_dump (PostgreSQL) <v>} only.
 *   <li>{@link ExpectedPostgresTool#PG_RESTORE} accepts canonical or {@code pg_restore (PostgreSQL) <v>}
 *       only.
 * </ul>
 *
 * <p>Each accepted shape may carry a single bounded, non-nested distro/build note drawn from a closed
 * ASCII package-version allowlist ({@code  (Ubuntu 16.4-1.pgdg22.04+1)}): ASCII letters, digits and
 * {@code . _ + ~ : -}, with single spaces between non-empty tokens. Everything else — a foreign tool's
 * banner, {@code v16}, {@code 16.}, empty output, an unrecognised tool name, an ambiguous multi-version
 * string, a banner carrying no parseable version, a note containing a quote / backslash / semicolon /
 * brace / bracket / nested parenthesis / control character / line separator, multiline/control noise, or
 * oversized input — raises {@link IllegalArgumentException}, which the control lifecycle boundary maps to
 * a bounded {@code 400 INVALID_BACKUP_ARTIFACT_REPORT} (never the raw banner). The extracted token is
 * always re-checked against the canonical contract before it is returned, so this normalizer can never
 * emit a non-canonical value into the domain / DB layer.
 */
public final class PostgresToolVersionNormalizer {
  private static final int MAX_RAW_LENGTH = 120;

  private static final Pattern CANONICAL = Pattern.compile(BackupArtifact.CANONICAL_TOOL_VERSION_REGEX);

  // Reused canonical MAJOR[.MINOR[.PATCH]] token.
  private static final String VERSION_GROUP = "([0-9]{1,3}(?:\\.[0-9]{1,3}){0,2})";

  // Single optional bounded distro/build note over a CLOSED ASCII package-version allowlist: ASCII
  // letters, digits, and '. _ + ~ : -', with single spaces between non-empty tokens (no leading,
  // trailing, or doubled spaces). This admits real approved suffixes such as
  // '(Ubuntu 16.4-1.pgdg22.04+1)' / '(Debian 16.4-1.pgdg120+1)' while structurally rejecting CR/LF/tab,
  // NUL/control and Unicode line/paragraph separators, quotes, backslashes, semicolons, braces/brackets,
  // and nested parentheses. The whole raw input is additionally length-bounded and control-scrubbed
  // before this pattern is ever applied.
  private static final String DISTRO_NOTE_TOKEN = "[A-Za-z0-9._+~:-]+";
  private static final String DISTRO_NOTE = DISTRO_NOTE_TOKEN + "(?: " + DISTRO_NOTE_TOKEN + ")*";
  private static final String OPTIONAL_DISTRO_NOTE = "(?: \\(" + DISTRO_NOTE + "\\))?";

  /**
   * The semantic identity of a reported PostgreSQL tool/server version. Each constant carries the bounded
   * field name used only for the fail-closed reason code (never the raw value) and the single bounded
   * producer shape defined for it. A field is normalized ONLY against its own producer shape, so one
   * tool's banner can never be accepted in another tool's field.
   *
   * <p>{@link #POSTGRES_SERVER} carries an empty prefix: its producer is {@code SHOW server_version}, whose
   * output is the canonical version optionally followed by a bounded package suffix, with no tool-name
   * prefix. {@link #PG_DUMP} and {@link #PG_RESTORE} carry the literal banner prefix their {@code
   * --version} output emits.
   */
  public enum ExpectedPostgresTool {
    POSTGRES_SERVER("postgresServerVersion", ""),
    PG_DUMP("pgDumpVersion", "pg_dump (PostgreSQL)"),
    PG_RESTORE("pgRestoreVersion", "pg_restore (PostgreSQL)");

    private final String field;
    private final Pattern banner;

    ExpectedPostgresTool(String field, String bannerPrefix) {
      this.field = field;
      // Server field (empty prefix): ^<canonical version>[ (<bounded distro note>)]$ — the SHOW
      // server_version producer form. Tool fields: ^<literal banner prefix> <canonical version>[ (…)]$.
      String core = bannerPrefix.isEmpty()
          ? VERSION_GROUP
          : Pattern.quote(bannerPrefix) + " " + VERSION_GROUP;
      this.banner = Pattern.compile("^" + core + OPTIONAL_DISTRO_NOTE + "$");
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
    // Strict, fixed order (never echoes the raw value in any reason code):
    // 1. null/blank; 2. maximum-length bound on the ORIGINAL raw String; 3. control/line-separator
    // rejection; 4. strip; 5. canonical / tool-specific full match; 6. canonical output revalidation.
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(tool.field() + "_REQUIRED");
    }
    // 2. Bound the ORIGINAL raw length before strip(), so whitespace padding can never smuggle an
    // oversized value (or a valid token buried in >MAX padding) past MAX_RAW_LENGTH.
    if (raw.length() > MAX_RAW_LENGTH) {
      throw new IllegalArgumentException(tool.field() + "_INVALID");
    }
    // 3. Reject any control character or Unicode line/paragraph separator anywhere in the raw input,
    // before strip() and before any pattern match, so no embedded CR/LF/tab/NUL/NEL/U+2028/U+2029 can
    // reach the anchored full-string matcher.
    if (containsControlOrLineSeparator(raw)) {
      throw new IllegalArgumentException(tool.field() + "_INVALID");
    }
    String trimmed = raw.strip();
    if (CANONICAL.matcher(trimmed).matches()) {
      return trimmed;
    }
    Matcher matcher = tool.banner().matcher(trimmed);
    if (matcher.matches()) {
      String canonical = matcher.group(1);
      // 6. Defence in depth: the extracted token must itself satisfy the canonical contract before it is
      // ever handed to the domain/DB layer, so this normalizer can never emit a non-canonical value.
      if (CANONICAL.matcher(canonical).matches()) {
        return canonical;
      }
    }
    throw new IllegalArgumentException(tool.field() + "_INVALID");
  }

  private static boolean containsControlOrLineSeparator(String raw) {
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      // Character.isISOControl covers U+0000–U+001F and U+007F–U+009F (CR, LF, tab, NUL, NEL, …); the two
      // explicit checks add the Unicode LINE SEPARATOR (U+2028) and PARAGRAPH SEPARATOR (U+2029).
      if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') {
        return true;
      }
    }
    return false;
  }
}
