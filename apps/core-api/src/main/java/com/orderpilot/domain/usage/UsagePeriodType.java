package com.orderpilot.domain.usage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * OP-CAP-16B Usage Metering Foundation — the aggregation window a counter accumulates over. The
 * {@code periodKey} is derived deterministically from an instant in UTC so the same instant always
 * maps to the same counter row.
 */
public enum UsagePeriodType {
  /** Calendar month, key format {@code yyyy-MM} (UTC). */
  MONTH("yyyy-MM"),
  /** Calendar day, key format {@code yyyy-MM-dd} (UTC). */
  DAY("yyyy-MM-dd"),
  /** Lifetime total; constant key {@code ALL}. */
  TOTAL(null);

  private final DateTimeFormatter formatter;

  UsagePeriodType(String pattern) {
    this.formatter =
        pattern == null ? null : DateTimeFormatter.ofPattern(pattern).withZone(ZoneOffset.UTC);
  }

  /** Deterministic, tenant-independent period key for the given instant. Never null/blank. */
  public String periodKey(Instant instant) {
    if (formatter == null) {
      return "ALL";
    }
    return formatter.format(instant);
  }
}
