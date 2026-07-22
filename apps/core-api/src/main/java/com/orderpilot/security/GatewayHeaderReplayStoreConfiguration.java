package com.orderpilot.security;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@Import(ControlPlaneCredentialRegistryConfiguration.class)
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
      @Value("${orderpilot.security.gateway-header-auth.redis.port:6379}") int port,
      @Value("${orderpilot.security.gateway-header-auth.redis.password:}") String password,
      @Value("${orderpilot.security.gateway-header-auth.redis.connect-timeout-ms:2000}") long connectTimeoutMs,
      @Value("${orderpilot.security.gateway-header-auth.redis.command-timeout-ms:2000}") long commandTimeoutMs) {
    RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
    if (password != null && !password.isBlank()) {
      configuration.setPassword(password);
    }
    // Explicit native bounds: a stalled Redis dependency times out at the driver level rather than
    // relying on Lettuce defaults. Both timeouts are server-owned and bounded; the password is only
    // set on the standalone configuration and never logged or surfaced.
    SocketOptions socketOptions = SocketOptions.builder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .build();
    LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
        .commandTimeout(Duration.ofMillis(commandTimeoutMs))
        .clientOptions(ClientOptions.builder().socketOptions(socketOptions).build())
        .build();
    return new LettuceConnectionFactory(configuration, clientConfiguration);
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
