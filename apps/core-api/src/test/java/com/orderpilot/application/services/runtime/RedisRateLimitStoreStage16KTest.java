package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-16K — unit tests for {@link RedisRateLimitStore} key construction, accumulation,
 * tenant/operation isolation, TTL derivation, and explicit fail-open/fail-closed behavior — all
 * without a running Redis, via a fake {@link RedisRateCounter}. The Redis client (Lettuce/Lua) lives
 * in {@code LettuceRedisRateCounter} and is exercised only against a real Redis (see the stage doc).
 */
class RedisRateLimitStoreStage16KTest {
  private static final long WINDOW = 60L;

  @Test
  void accumulatesWeightPerKeyAndWindow() {
    FakeCounter counter = new FakeCounter();
    RedisRateLimitStore store = new RedisRateLimitStore(counter, "orderpilot:runtime-rate", 5L, false);
    String key = UUID.randomUUID() + ":AI_DOCUMENT_EXTRACTION";

    assertThat(store.addAndGet(key, 1000L, WINDOW, 8L)).isEqualTo(8L);
    assertThat(store.addAndGet(key, 1000L, WINDOW, 8L)).isEqualTo(16L);
  }

  @Test
  void differentWindowStartsAreSeparateCounters() {
    FakeCounter counter = new FakeCounter();
    RedisRateLimitStore store = new RedisRateLimitStore(counter, "p", 5L, false);
    String key = "t:OP";

    assertThat(store.addAndGet(key, 1000L, WINDOW, 4L)).isEqualTo(4L);
    assertThat(store.addAndGet(key, 1060L, WINDOW, 4L)).isEqualTo(4L);
  }

  @Test
  void tenantAndOperationKeysAreIsolated() {
    FakeCounter counter = new FakeCounter();
    RedisRateLimitStore store = new RedisRateLimitStore(counter, "p", 5L, false);

    assertThat(store.addAndGet("tenantA:OP", 1000L, WINDOW, 5L)).isEqualTo(5L);
    assertThat(store.addAndGet("tenantB:OP", 1000L, WINDOW, 5L)).isEqualTo(5L);
    assertThat(store.addAndGet("tenantA:OTHER", 1000L, WINDOW, 5L)).isEqualTo(5L);
    assertThat(counter.store).hasSize(3);
  }

  @Test
  void ttlIsWindowPlusBuffer() {
    FakeCounter counter = new FakeCounter();
    RedisRateLimitStore store = new RedisRateLimitStore(counter, "p", 5L, false);

    store.addAndGet("t:OP", 1000L, WINDOW, 1L);

    assertThat(counter.lastTtlSeconds).isEqualTo(WINDOW + 5L);
  }

  @Test
  void keyContainsPrefixWindowAndIsNormalized() {
    FakeCounter counter = new FakeCounter();
    RedisRateLimitStore store = new RedisRateLimitStore(counter, "orderpilot:runtime-rate", 5L, false);

    store.addAndGet("tenant 1:OP", 1000L, WINDOW, 1L);

    String key = counter.store.keySet().iterator().next();
    assertThat(key).startsWith("orderpilot:runtime-rate:").endsWith(":1000").doesNotContain(" ");
  }

  @Test
  void failClosedSaturatesWhenRedisUnavailable() {
    RedisRateLimitStore store = new RedisRateLimitStore(new ThrowingCounter(), "p", 5L, false);

    assertThat(store.addAndGet("t:OP", 1000L, WINDOW, 1L)).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void failOpenReturnsRequestWeightWhenRedisUnavailable() {
    RedisRateLimitStore store = new RedisRateLimitStore(new ThrowingCounter(), "p", 5L, true);

    assertThat(store.addAndGet("t:OP", 1000L, WINDOW, 7L)).isEqualTo(7L);
  }

  private static final class FakeCounter implements RedisRateCounter {
    private final Map<String, Long> store = new LinkedHashMap<>();
    private long lastTtlSeconds;

    @Override
    public long incrementAndGet(String key, long delta, long ttlSeconds) {
      lastTtlSeconds = ttlSeconds;
      return store.merge(key, delta, Long::sum);
    }
  }

  private static final class ThrowingCounter implements RedisRateCounter {
    @Override
    public long incrementAndGet(String key, long delta, long ttlSeconds) {
      throw new IllegalStateException("redis down");
    }
  }
}
