package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class GatewayHeaderReplayStoreConfigurationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withBean(Clock.class, Clock::systemUTC)
      .withUserConfiguration(GatewayHeaderReplayStoreConfiguration.class);

  @Test
  void testProfileAllowsInMemoryReplayStore() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasSingleBean(GatewayHeaderReplayAdmissionStore.class);
      assertThat(context.getBean(GatewayHeaderReplayAdmissionStore.class))
          .isInstanceOf(GatewayHeaderReplayGuard.class);
    });
  }

  @Test
  void redisReplayStoreConfigurationSelectsRedisStore() {
    runner
        .withPropertyValues("orderpilot.security.gateway-header-auth.replay-store=redis")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(GatewayHeaderReplayAdmissionStore.class);
          assertThat(context).hasSingleBean(LettuceConnectionFactory.class);
          assertThat(context).hasSingleBean(StringRedisTemplate.class);
          assertThat(context.getBean(GatewayHeaderReplayAdmissionStore.class))
              .isInstanceOf(RedisGatewayHeaderReplayAdmissionStore.class);
        });
  }
  @Test
  void redisReplayStoreConfigurationAcceptsPasswordProperty() {
    runner
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.replay-store=redis",
            "orderpilot.security.gateway-header-auth.redis.password=redis-secret-for-test")
        .run(context -> {
          assertThat(context).hasNotFailed();
          LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
          assertThat(factory.getStandaloneConfiguration().getPassword().isPresent()).isTrue();
          assertThat(new String(factory.getStandaloneConfiguration().getPassword().get()))
              .isEqualTo("redis-secret-for-test");
        });
  }
}
