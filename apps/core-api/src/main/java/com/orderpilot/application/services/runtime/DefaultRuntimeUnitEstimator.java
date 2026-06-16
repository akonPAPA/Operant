package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16F Runtime Unit Estimation — the default cheap estimator.
 *
 * <p>Estimation policy (uses only metadata already supplied; no parsing, no I/O):
 *
 * <ul>
 *   <li>{@code AI_DOCUMENT_EXTRACTION}: page count → {@code ceil(sizeBytes / 512KB)} → {@code
 *       ceil(lineCount / 25)} → 1
 *   <li>{@code BULK_IMPORT}: {@code ceil(rowCount / 100)} → {@code ceil(sizeBytes / 1MB)} → 1
 *   <li>{@code RECONCILIATION_RUN}: {@code ceil(itemCount / 100)} → 1
 *   <li>{@code REPORT_GENERATED}: {@code ceil(rowCount / 1000)} → 1
 *   <li>{@code AI_VALIDATION_EXPLANATION}: {@code ceil(lineCount / 25)} → message/item count → 1
 *   <li>otherwise: message count → 1
 * </ul>
 *
 * <p>Every result is clamped to {@code [1, MAX_UNITS]} and overflow-safe.
 */
public class DefaultRuntimeUnitEstimator implements RuntimeUnitEstimator {
  static final long MAX_UNITS = 100_000L;
  private static final long BYTES_PER_PAGE = 512L * 1024L;
  private static final long BYTES_PER_IMPORT_UNIT = 1024L * 1024L;
  private static final long LINES_PER_UNIT = 25L;
  private static final long ROWS_PER_IMPORT_UNIT = 100L;
  private static final long ROWS_PER_RECON_UNIT = 100L;
  private static final long ROWS_PER_REPORT_UNIT = 1000L;

  @Override
  public int estimate(RuntimeUnitEstimateRequest request) {
    if (request == null || request.operationType() == null) {
      return clamp(positiveOrZero(request == null ? null : request.knownMessageCount()));
    }
    return switch (request.operationType()) {
      case AI_DOCUMENT_EXTRACTION -> {
        long pages = positiveOrZero(request.knownPageCount());
        if (pages > 0) yield clamp(pages);
        long bytes = positiveOrZero(request.knownSizeBytes());
        if (bytes > 0) yield clamp(ceilDiv(bytes, BYTES_PER_PAGE));
        yield clamp(ceilDiv(positiveOrZero(request.knownLineCount()), LINES_PER_UNIT));
      }
      case BULK_IMPORT -> {
        long rows = positiveOrZero(request.knownRowCount());
        if (rows > 0) yield clamp(ceilDiv(rows, ROWS_PER_IMPORT_UNIT));
        yield clamp(ceilDiv(positiveOrZero(request.knownSizeBytes()), BYTES_PER_IMPORT_UNIT));
      }
      case RECONCILIATION_RUN ->
          clamp(ceilDiv(positiveOrZero(request.knownRowCount()), ROWS_PER_RECON_UNIT));
      case REPORT_GENERATED ->
          clamp(ceilDiv(positiveOrZero(request.knownRowCount()), ROWS_PER_REPORT_UNIT));
      case AI_VALIDATION_EXPLANATION -> {
        long lines = positiveOrZero(request.knownLineCount());
        if (lines > 0) yield clamp(ceilDiv(lines, LINES_PER_UNIT));
        yield clamp(positiveOrZero(request.knownMessageCount()));
      }
      default -> clamp(positiveOrZero(request.knownMessageCount()));
    };
  }

  /** Null/negative → 0; otherwise the value as a long (cannot overflow from Integer/Long inputs). */
  private static long positiveOrZero(Number value) {
    if (value == null) {
      return 0L;
    }
    long v = value.longValue();
    return v < 0 ? 0L : v;
  }

  /** Overflow-safe ceiling division for non-negative operands. */
  private static long ceilDiv(long numerator, long divisor) {
    if (numerator <= 0 || divisor <= 0) {
      return 0L;
    }
    return (numerator / divisor) + (numerator % divisor == 0 ? 0L : 1L);
  }

  /** Clamp into {@code [1, MAX_UNITS]} and narrow to int. */
  private static int clamp(long units) {
    if (units < 1L) {
      return 1;
    }
    if (units > MAX_UNITS) {
      return (int) MAX_UNITS;
    }
    return (int) units;
  }
}
