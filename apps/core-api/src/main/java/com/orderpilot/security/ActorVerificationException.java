package com.orderpilot.security;

/**
 * OP-CAP-16K — raised when a runtime entitlement admin mutation requires a verified (signed) actor
 * and the signature is missing, invalid, stale, or malformed while a signing secret is configured.
 * Mapped to HTTP 401 by {@code GlobalExceptionHandler}. Carries only a stable, non-sensitive message
 * (never the expected signature or the signing secret).
 */
public class ActorVerificationException extends RuntimeException {
  public ActorVerificationException(String message) {
    super(message);
  }
}
