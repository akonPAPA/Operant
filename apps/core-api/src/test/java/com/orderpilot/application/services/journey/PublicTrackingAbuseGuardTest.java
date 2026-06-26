package com.orderpilot.application.services.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.orderpilot.application.services.runtime.InMemoryRateLimitStore;
import com.orderpilot.application.services.runtime.RateLimitStore;
import com.orderpilot.common.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Stage 9 — Public Tracking Abuse Hardening: unit proof of the guard primitive itself.
 *
 * <p>Proves the durable invariants independent of the HTTP layer: token-free keying, per-client
 * isolation, deterministic over-limit denial with a Retry-After, no tenant/permission dependency, and
 * that a missing client identifier is still bucketed (cannot be used to bypass the guard).
 */
class PublicTrackingAbuseGuardTest {
  private static final Instant FIXED = Instant.parse("2026-06-26T12:00:30Z");
  private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private PublicTrackingAbuseGuard guard(RateLimitStore store, long window, long max) {
    return new PublicTrackingAbuseGuard(store, clock, window, max);
  }

  @Test
  void allowsAttemptsUpToTheBudgetThenDeniesWithARetryAfter() {
    PublicTrackingAbuseGuard guard = guard(new InMemoryRateLimitStore(), 60, 3);

    // Exactly the budget is allowed.
    for (int i = 0; i < 3; i++) {
      assertThatCode(() -> guard.checkAndConsume("198.51.100.7")).doesNotThrowAnyException();
    }

    // The next attempt in the same window is denied with a positive Retry-After.
    PublicTrackingRateLimitedException ex = catchThrowableOfType(
        () -> guard.checkAndConsume("198.51.100.7"), PublicTrackingRateLimitedException.class);
    assertThat(ex).isNotNull();
    assertThat(ex.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1L);
    // Generic message — no token, no journey, no tenant.
    assertThat(ex.getMessage()).doesNotContainIgnoringCase("token").doesNotContainIgnoringCase("journey");
  }

  @Test
  void abuseKeyIsTokenFreeAndPerClientIsolated() {
    PublicTrackingAbuseGuard guard = guard(new InMemoryRateLimitStore(), 60, 2);

    // One client exhausts its own budget.
    guard.checkAndConsume("203.0.113.9");
    guard.checkAndConsume("203.0.113.9");
    assertThatThrownBy(() -> guard.checkAndConsume("203.0.113.9"))
        .isInstanceOf(PublicTrackingRateLimitedException.class);

    // A different client is unaffected (independent bucket).
    assertThatCode(() -> guard.checkAndConsume("203.0.113.10")).doesNotThrowAnyException();

    // The derived counter key is a hash, never the raw identifier (and certainly never any token).
    String rawClient = "203.0.113.9";
    String hash = PublicTrackingAbuseGuard.clientKeyHash(rawClient);
    assertThat(hash).isNotEqualTo(rawClient).hasSize(64).matches("[0-9a-f]+");
    assertThat(hash).doesNotContain(rawClient);
  }

  @Test
  void requiresNoTenantOrPermissionContext() {
    // No TenantContext is set and no permission header exists; the guard must still function.
    assertThat(TenantContext.getTenantId()).isEmpty();
    PublicTrackingAbuseGuard guard = guard(new InMemoryRateLimitStore(), 60, 1);

    assertThatCode(() -> guard.checkAndConsume("192.0.2.1")).doesNotThrowAnyException();
    assertThatThrownBy(() -> guard.checkAndConsume("192.0.2.1"))
        .isInstanceOf(PublicTrackingRateLimitedException.class);
    assertThat(TenantContext.getTenantId()).isEmpty();
  }

  @Test
  void missingClientIdentifierIsStillBucketedSoItCannotBypassTheGuard() {
    PublicTrackingAbuseGuard guard = guard(new InMemoryRateLimitStore(), 60, 2);

    // null and blank both fold into the same stable shared bucket and are rate-limited together.
    guard.checkAndConsume(null);
    guard.checkAndConsume("   ");
    assertThatThrownBy(() -> guard.checkAndConsume(null))
        .isInstanceOf(PublicTrackingRateLimitedException.class);

    assertThat(PublicTrackingAbuseGuard.clientKeyHash(null))
        .isEqualTo(PublicTrackingAbuseGuard.clientKeyHash("  "));
  }

  @Test
  void windowResetClearsTheBudget() {
    InMemoryRateLimitStore store = new InMemoryRateLimitStore();
    PublicTrackingAbuseGuard firstWindow = guard(store, 60, 1);
    firstWindow.checkAndConsume("198.51.100.50");
    assertThatThrownBy(() -> firstWindow.checkAndConsume("198.51.100.50"))
        .isInstanceOf(PublicTrackingRateLimitedException.class);

    // A guard whose clock sits in the next fixed window starts a fresh budget for the same client.
    Clock nextWindow = Clock.fixed(FIXED.plusSeconds(60), ZoneOffset.UTC);
    PublicTrackingAbuseGuard rolled = new PublicTrackingAbuseGuard(store, nextWindow, 60, 1);
    assertThatCode(() -> rolled.checkAndConsume("198.51.100.50")).doesNotThrowAnyException();
  }
}
