package com.orderpilot.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OP-CAP-43E - bounded, single-use admission store for gateway-signed authority headers.
 *
 * <p>OP-CAP-43C proved the HMAC verifier rejects forged/expired/tampered signatures, but a captured,
 * still-fresh signed request could be replayed inside the {@code clock-skew-seconds} window and reuse
 * its tenant/actor/permission authority. This store closes that window for single-instance/dev/test
 * mode: a per-request nonce (bound into the HMAC canonical string) may be admitted at most once
 * within the retention window.
 *
 * <p>Storage is an in-memory {@link ConcurrentHashMap} keyed by a digest of tenant/actor/nonce,
 * never a raw tenant id, actor id, nonce, secret, signature, or canonical string. Expiry is safe
 * against replay: any request older than the freshness window is already rejected by the verifier's
 * timestamp check before it reaches this store, so forgetting an expired nonce can never re-open a
 * replay.
 *
 * <p>Growth is bounded by {@code maxEntries}: expired entries are purged opportunistically on each
 * admission, and if the live set is still at capacity a previously-unseen nonce is rejected
 * (fail-closed) rather than evicting a live entry and silently allowing a replay.
 *
 * <p>Single-instance only: this store is per-application-instance. Multi-instance production requires
 * {@link RedisGatewayHeaderReplayAdmissionStore} or gateway-level replay prevention.
 */
final class GatewayHeaderReplayGuard implements GatewayHeaderReplayAdmissionStore {
  private static final String KEY_PREFIX = "op:gw-replay";

  private final Clock clock;
  private final int maxEntries;
  private final ConcurrentHashMap<String, Long> firstSeenEpochByKey = new ConcurrentHashMap<>();

  GatewayHeaderReplayGuard(Clock clock, int maxEntries) {
    this.clock = clock;
    this.maxEntries = Math.max(1, maxEntries);
  }

  /**
   * Atomically admit the first use of tenant/actor/nonce; reject any duplicate seen within the
   * retention window.
   *
   * @return {@code true} on first admission, {@code false} on replay or capacity overflow.
   */
  @Override
  public boolean admitFirstUse(String tenantId, String actorId, String nonce, Duration ttl) {
    String replayKey = GatewayHeaderReplayKey.digestKey(KEY_PREFIX, tenantId, actorId, nonce);
    long nowEpoch = clock.instant().getEpochSecond();
    purgeExpired(nowEpoch, Math.max(1L, ttl.getSeconds()));
    if (firstSeenEpochByKey.size() >= maxEntries && !firstSeenEpochByKey.containsKey(replayKey)) {
      return false;
    }
    return firstSeenEpochByKey.putIfAbsent(replayKey, nowEpoch) == null;
  }

  private void purgeExpired(long nowEpoch, long retentionSeconds) {
    firstSeenEpochByKey.entrySet().removeIf(entry -> nowEpoch - entry.getValue() > retentionSeconds);
  }
}
