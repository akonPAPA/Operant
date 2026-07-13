package com.orderpilot.common.api;

import java.util.regex.Pattern;

/**
 * Validates client-supplied idempotency tokens from the {@code Idempotency-Key} header. Values are
 * never logged by this helper and are never trimmed or normalized.
 *
 * <p>The grammar below is the canonical, single-source idempotency-key contract shared with the BFF
 * (shared/contracts/idempotency-key-contract.json and bff-idempotency-key.ts). It must not
 * drift: {@code ClientIdempotencyKeyContractParityTest} asserts these constants equal the JSON
 * contract, and the BFF asserts its embedded copy equals the same file. The grammar deliberately
 * excludes {@code ~} (a historical BFF-only character that Core rejected) so a token accepted at the
 * browser boundary is byte-for-byte acceptable here.
 */
public final class ClientIdempotencyKey {
  public static final int MIN_LENGTH = 1;
  public static final int MAX_LENGTH = 128;
  public static final String CANONICAL_PATTERN = "^[A-Za-z0-9._:-]+$";

  private static final Pattern CANONICAL = Pattern.compile(CANONICAL_PATTERN);

  private ClientIdempotencyKey() {}

  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    if (raw.length() < MIN_LENGTH) {
      throw new IllegalArgumentException("Idempotency-Key must not be empty");
    }
    if (raw.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("Idempotency-Key exceeds maximum length");
    }
    if (!CANONICAL.matcher(raw).matches()) {
      throw new IllegalArgumentException("Idempotency-Key contains disallowed characters");
    }
    return raw;
  }
}
