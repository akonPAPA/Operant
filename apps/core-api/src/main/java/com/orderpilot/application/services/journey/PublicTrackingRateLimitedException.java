package com.orderpilot.application.services.journey;

/**
 * Stage 9 — Public Tracking Abuse Hardening.
 *
 * <p>Thrown when the unauthenticated public secure-tracking endpoint
 * ({@code GET /api/v1/public/order-tracking/{token}}) is hit too frequently from one client within the
 * fixed window. Maps to HTTP 429 {@code PUBLIC_TRACKING_RATE_LIMITED} with a {@code Retry-After} hint.
 *
 * <p>This denial is decided BEFORE any token lookup and is keyed on a hashed client identifier — never
 * the raw token. The message is intentionally generic and reveals nothing about whether a token exists,
 * is valid, expired, revoked, or malformed (no enumeration oracle). The raw token never reaches this
 * exception.
 */
public class PublicTrackingRateLimitedException extends RuntimeException {
  private final long retryAfterSeconds;

  public PublicTrackingRateLimitedException(long retryAfterSeconds) {
    super("Too many tracking requests; please retry later");
    this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
