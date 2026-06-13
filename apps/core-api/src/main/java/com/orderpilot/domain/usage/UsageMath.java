package com.orderpilot.domain.usage;

/**
 * OP-CAP-16B Usage Metering Foundation — overflow-safe arithmetic helpers for usage counters and
 * quota checks.
 *
 * <p>All accumulation uses {@code long} and saturates at {@link Long#MAX_VALUE} rather than wrapping.
 * Any user-controlled or external value used in a counter/limit must pass through these helpers so a
 * crafted large input can never produce a negative or wrapped total (CodeQL arithmetic-overflow
 * hygiene).
 */
public final class UsageMath {
  private UsageMath() {}

  /** Saturating addition: returns {@code Long.MAX_VALUE} instead of overflowing. */
  public static long safeAdd(long a, long b) {
    long sa = Math.max(0L, a);
    long sb = Math.max(0L, b);
    long sum = sa + sb;
    // Positive-overflow detection: wrapping makes the sum smaller than an operand.
    if (sum < sa || sum < sb) {
      return Long.MAX_VALUE;
    }
    return sum;
  }

  /** Clamp any value (including a negative or wrapped input) into {@code [0, Long.MAX_VALUE]}. */
  public static long clampNonNegative(long value) {
    return Math.max(0L, value);
  }

  /**
   * Remaining headroom under {@code limit} given {@code used}, never negative. Returns 0 when at or
   * over limit.
   */
  public static long remaining(long limit, long used) {
    long safeLimit = Math.max(0L, limit);
    long safeUsed = Math.max(0L, used);
    if (safeUsed >= safeLimit) {
      return 0L;
    }
    return safeLimit - safeUsed;
  }
}
