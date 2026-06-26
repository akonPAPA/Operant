package com.orderpilot.application.services.journey;

import com.orderpilot.application.services.runtime.RateLimitStore;
import com.orderpilot.application.services.runtime.RetryAfterPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stage 9 — Public Tracking Abuse Hardening.
 *
 * <p>The smallest durable abuse-hardening layer for the unauthenticated public secure-tracking route
 * ({@code GET /api/v1/public/order-tracking/{token}}). It reuses the existing swappable fixed-window
 * counter primitive ({@link RateLimitStore}; in-memory by default, Redis-backed when configured) rather
 * than introducing a parallel rate-limit architecture, and the existing deterministic {@link
 * RetryAfterPolicy}.
 *
 * <p>Design invariants (Stage 9 hardening contract):
 * <ul>
 *   <li>The guard is checked BEFORE any token lookup, so a denial is independent of token validity —
 *       it cannot reveal whether a token is unknown, valid, expired, revoked, or malformed.</li>
 *   <li>The abuse key is derived from a hashed client identifier (e.g. the connection remote address),
 *       <b>never</b> the raw token. The raw token is never passed to, held by, or logged by this guard.</li>
 *   <li>No tenant header, operator permission, actor, or any client-supplied authority is required or
 *       consulted — the public route stays public.</li>
 *   <li>Window length and per-window budget are deterministic (injected {@link Clock} + configuration)
 *       so the behaviour is testable without timing flakiness.</li>
 * </ul>
 */
@Component
public class PublicTrackingAbuseGuard {
  /** Stable, token-free namespace for the abuse counter key. */
  static final String KEY_PREFIX = "public-order-tracking:";

  static final long DEFAULT_WINDOW_SECONDS = 60L;
  static final long DEFAULT_MAX_ATTEMPTS = 30L;

  private final RateLimitStore store;
  private final Clock clock;
  private final long windowSeconds;
  private final long maxAttempts;

  public PublicTrackingAbuseGuard(
      RateLimitStore store,
      Clock clock,
      @Value("${orderpilot.security.public-tracking.rate.window-seconds:60}") long windowSeconds,
      @Value("${orderpilot.security.public-tracking.rate.max-attempts:30}") long maxAttempts) {
    this.store = store;
    this.clock = clock;
    this.windowSeconds = windowSeconds > 0 ? windowSeconds : DEFAULT_WINDOW_SECONDS;
    this.maxAttempts = maxAttempts > 0 ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
  }

  /**
   * Count this public-tracking attempt for {@code clientIdentifier} and deny (throw {@link
   * PublicTrackingRateLimitedException}) once the per-window budget is exceeded. The identifier is
   * hashed before it becomes a counter key, so neither a raw client address nor (critically) any token
   * is stored. A blank/unknown identifier is bucketed under a stable shared key rather than skipped, so
   * an attacker cannot bypass the guard by suppressing the client address.
   *
   * @param clientIdentifier opaque per-client value (e.g. remote address); MUST NOT be the raw token
   */
  public void checkAndConsume(String clientIdentifier) {
    long nowEpoch = clock.instant().getEpochSecond();
    long windowStart = Math.floorDiv(nowEpoch, windowSeconds) * windowSeconds;
    String key = KEY_PREFIX + clientKeyHash(clientIdentifier);

    long used = store.addAndGet(key, windowStart, windowSeconds, 1L);
    if (used > maxAttempts) {
      throw new PublicTrackingRateLimitedException(
          RetryAfterPolicy.retryAfterSeconds(nowEpoch, windowStart, windowSeconds));
    }
  }

  /**
   * Hash the client identifier so the counter key (and anything derived from it) carries no raw client
   * address. A null/blank identifier maps to a stable shared bucket so it is still rate-limited.
   */
  static String clientKeyHash(String clientIdentifier) {
    String basis = (clientIdentifier == null || clientIdentifier.isBlank())
        ? "unknown-client"
        : clientIdentifier.trim();
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required for public tracking abuse-guard keys", ex);
    }
  }
}
