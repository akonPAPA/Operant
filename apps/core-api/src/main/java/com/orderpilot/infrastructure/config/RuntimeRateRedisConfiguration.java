package com.orderpilot.infrastructure.config;

import com.orderpilot.application.services.runtime.RateLimitStore;
import com.orderpilot.application.services.runtime.RedisRateCounter;
import com.orderpilot.application.services.runtime.RedisRateLimitStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * OP-CAP-16K — wires the distributed Redis rate-limit store, and only when
 * {@code orderpilot.runtime.rate.store=redis}. With any other value (default {@code in-memory}) none
 * of these beans exist and no Redis connection is created — combined with excluding Spring Boot's
 * {@code RedisAutoConfiguration} (see {@code application.yml}), the app never touches Redis unless the
 * distributed store is explicitly selected.
 *
 * <p>Providing a {@link RateLimitStore} bean here makes the {@code @ConditionalOnMissingBean} default
 * in {@code CoreConfiguration} back off; that default is additionally property-gated to
 * {@code in-memory}, so the two are mutually exclusive and selection is deterministic.
 *
 * <p>Redis is used here strictly for rate counters — never for plans, entitlements, quota, audit, or
 * business state.
 */
@Configuration
@ConditionalOnProperty(name = "orderpilot.runtime.rate.store", havingValue = "redis")
public class RuntimeRateRedisConfiguration {

  @Bean
  LettuceConnectionFactory runtimeRateRedisConnectionFactory(
      @Value("${orderpilot.runtime.rate.redis.host:localhost}") String host,
      @Value("${orderpilot.runtime.rate.redis.port:6379}") int port) {
    return new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
  }

  @Bean
  StringRedisTemplate runtimeRateRedisTemplate(LettuceConnectionFactory runtimeRateRedisConnectionFactory) {
    return new StringRedisTemplate(runtimeRateRedisConnectionFactory);
  }

  @Bean
  RedisRateCounter runtimeRateRedisCounter(StringRedisTemplate runtimeRateRedisTemplate) {
    return new LettuceRedisRateCounter(runtimeRateRedisTemplate);
  }

  @Bean
  RateLimitStore redisRateLimitStore(
      RedisRateCounter runtimeRateRedisCounter,
      @Value("${orderpilot.runtime.rate.redis.key-prefix:orderpilot:runtime-rate}") String keyPrefix,
      @Value("${orderpilot.runtime.rate.redis.fail-open:false}") boolean failOpen) {
    // TTL buffer keeps the counter alive slightly past the window so a request landing exactly at the
    // boundary still sees a consistent count.
    return new RedisRateLimitStore(runtimeRateRedisCounter, keyPrefix, 5L, failOpen);
  }
}
