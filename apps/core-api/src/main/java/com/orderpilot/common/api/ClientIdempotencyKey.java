package com.orderpilot.common.api;

/**
 * Normalizes client-supplied idempotency tokens from the {@code Idempotency-Key} header. Values are
 * never logged by this helper.
 */
public final class ClientIdempotencyKey {
  public static final int MAX_LENGTH = 128;

  private ClientIdempotencyKey() {}

  public static String normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String trimmed = raw.strip();
    if (trimmed.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("Idempotency-Key exceeds maximum length");
    }
    if (!trimmed.matches("[A-Za-z0-9_:\\-.]+")) {
      throw new IllegalArgumentException("Idempotency-Key contains disallowed characters");
    }
    return trimmed;
  }
}
