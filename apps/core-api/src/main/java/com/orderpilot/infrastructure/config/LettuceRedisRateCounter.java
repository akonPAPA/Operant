package com.orderpilot.infrastructure.config;

import com.orderpilot.application.services.runtime.RedisRateCounter;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * OP-CAP-16K — production {@link RedisRateCounter} backed by a Lettuce {@link StringRedisTemplate}.
 *
 * <p>Atomicity: a single Lua script performs {@code INCRBY} and sets {@code EXPIRE} only on the first
 * write of the window (when the returned value equals the just-added delta). Doing both in one script
 * removes the INCR-succeeds-but-EXPIRE-missing race a separate INCR + EXPIRE would have.
 */
public class LettuceRedisRateCounter implements RedisRateCounter {
  private static final RedisScript<Long> INCR_WITH_TTL =
      new DefaultRedisScript<>(
          "local v = redis.call('INCRBY', KEYS[1], ARGV[1])\n"
              + "if v == tonumber(ARGV[1]) then\n"
              + "  redis.call('EXPIRE', KEYS[1], ARGV[2])\n"
              + "end\n"
              + "return v",
          Long.class);

  private final StringRedisTemplate redisTemplate;

  public LettuceRedisRateCounter(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  @Override
  public long incrementAndGet(String key, long delta, long ttlSeconds) {
    Long value =
        redisTemplate.execute(
            INCR_WITH_TTL, List.of(key), Long.toString(delta), Long.toString(ttlSeconds));
    if (value == null) {
      throw new IllegalStateException("Redis rate counter returned no value");
    }
    return value;
  }
}
