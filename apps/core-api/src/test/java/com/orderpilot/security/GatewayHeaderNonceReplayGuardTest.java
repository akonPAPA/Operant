package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-43E/43F - focused unit test of the in-memory single-use admission store.
 *
 * <p>The MockMvc replay test proves end-to-end behavior over the real security chain. This class
 * isolates the store mechanics with a controllable {@link Clock} so TTL/expiry and bounded-growth
 * properties can be proven deterministically.
 */
class GatewayHeaderNonceReplayGuardTest {

  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-23T00:00:00Z"));

  private GatewayHeaderReplayGuard guard(int maxEntries) {
    Clock movableClock = new Clock() {
      @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(java.time.ZoneId zone) { return this; }
      @Override public Instant instant() { return now.get(); }
    };
    return new GatewayHeaderReplayGuard(movableClock, maxEntries);
  }

  private void advanceSeconds(long seconds) {
    now.updateAndGet(current -> current.plusSeconds(seconds));
  }

  @Test
  void firstUseIsAdmittedAndImmediateReplayIsRejected() {
    GatewayHeaderReplayGuard guard = guard(1000);
    assertThat(admit(guard, "tenant", "actor", "nonce-1")).isTrue();
    assertThat(admit(guard, "tenant", "actor", "nonce-1")).isFalse();
  }

  @Test
  void distinctKeysAreIndependent() {
    GatewayHeaderReplayGuard guard = guard(1000);
    assertThat(admit(guard, "tenant-a", "actor", "nonce")).isTrue();
    assertThat(admit(guard, "tenant-b", "actor", "nonce")).isTrue();
  }

  @Test
  void replayWithinRetentionWindowIsRejected() {
    GatewayHeaderReplayGuard guard = guard(1000);
    assertThat(admit(guard, "tenant", "actor", "nonce")).isTrue();
    advanceSeconds(599);
    assertThat(admit(guard, "tenant", "actor", "nonce")).isFalse();
  }

  @Test
  void expiredEntryIsForgottenButThisDoesNotEnableReplay() {
    GatewayHeaderReplayGuard guard = guard(1000);
    assertThat(admit(guard, "tenant", "actor", "nonce")).isTrue();
    advanceSeconds(601);
    assertThat(admit(guard, "tenant", "actor", "nonce")).isTrue();
  }

  @Test
  void boundedStoreFailsClosedWhenAtCapacityForUnseenKey() {
    GatewayHeaderReplayGuard guard = guard(2);
    assertThat(admit(guard, "tenant", "actor", "a")).isTrue();
    assertThat(admit(guard, "tenant", "actor", "b")).isTrue();
    assertThat(admit(guard, "tenant", "actor", "c")).isFalse();
    assertThat(admit(guard, "tenant", "actor", "a")).isFalse();
  }

  private static boolean admit(GatewayHeaderReplayGuard guard, String tenant, String actor, String nonce) {
    return guard.admitFirstUse(tenant, actor, nonce, Duration.ofSeconds(600));
  }
}
