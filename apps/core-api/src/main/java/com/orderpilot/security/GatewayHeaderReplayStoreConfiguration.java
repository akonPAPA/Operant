package com.orderpilot.security;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
class GatewayHeaderReplayStoreConfiguration {
  static final int DEFAULT_MEMORY_MAX_ENTRIES = 100_000;

  @Bean
  @ConditionalOnProperty(
      name = "orderpilot.security.gateway-header-auth.replay-store",
      havingValue = "memory",
      matchIfMissing = true)
  GatewayHeaderReplayAdmissionStore inMemoryGatewayHeaderReplayAdmissionStore(Clock clock) {
    return new GatewayHeaderReplayGuard(clock, DEFAULT_MEMORY_MAX_ENTRIES);
  }

  @Bean("gatewayHeaderReplayRedisConnectionFactory")
  @ConditionalOnProperty(name = "orderpilot.security.gateway-header-auth.replay-store", havingValue = "redis")
  LettuceConnectionFactory gatewayHeaderReplayRedisConnectionFactory(
      @Value("${orderpilot.security.gateway-header-auth.redis.host:localhost}") String host,
      @Value("${orderpilot.security.gateway-header-auth.redis.port:6379}") int port) {
    return new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
  }

  @Bean("gatewayHeaderReplayRedisTemplate")
  @ConditionalOnProperty(name = "orderpilot.security.gateway-header-auth.replay-store", havingValue = "redis")
  StringRedisTemplate gatewayHeaderReplayRedisTemplate(
      @Qualifier("gatewayHeaderReplayRedisConnectionFactory")
          LettuceConnectionFactory gatewayHeaderReplayRedisConnectionFactory) {
    return new StringRedisTemplate(gatewayHeaderReplayRedisConnectionFactory);
  }

  @Bean
  @ConditionalOnProperty(name = "orderpilot.security.gateway-header-auth.replay-store", havingValue = "redis")
  GatewayHeaderReplayAdmissionStore redisGatewayHeaderReplayAdmissionStore(
      @Qualifier("gatewayHeaderReplayRedisTemplate") StringRedisTemplate redisTemplate,
      @Value("${orderpilot.security.gateway-header-auth.redis.key-prefix:op:gw-replay}") String keyPrefix) {
    return new RedisGatewayHeaderReplayAdmissionStore(redisTemplate, keyPrefix);
  }
}
