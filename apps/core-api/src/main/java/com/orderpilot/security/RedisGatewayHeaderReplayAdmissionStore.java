package com.orderpilot.security;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed distributed first-use admission for gateway replay nonces.
 *
 * <p>Uses Redis SET NX with expiry via Spring Data Redis {@code setIfAbsent(key, value, ttl)}.
 * Redis errors fail closed by returning {@code false}; callers then reject authentication without
 * exposing Redis details, nonce material, or signing internals to the client.
 */
final class RedisGatewayHeaderReplayAdmissionStore implements GatewayHeaderReplayAdmissionStore {
  private static final String MARKER = "1";

  private final StringRedisTemplate redisTemplate;
  private final String keyPrefix;

  RedisGatewayHeaderReplayAdmissionStore(StringRedisTemplate redisTemplate, String keyPrefix) {
    this.redisTemplate = redisTemplate;
    this.keyPrefix = keyPrefix;
  }

  @Override
  public boolean admitFirstUse(String tenantId, String actorId, String nonce, Duration ttl) {
    String key = GatewayHeaderReplayKey.digestKey(keyPrefix, tenantId, actorId, nonce);
    try {
      return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, MARKER, ttl));
    } catch (RuntimeException ex) {
      return false;
    }
  }
}
