package com.orderpilot.infrastructure.config;

import com.orderpilot.application.services.connector.LocalDevelopmentSecretVaultService;
import com.orderpilot.application.services.connector.SecretVaultService;
import com.orderpilot.application.services.runtime.InMemoryRateLimitStore;
import com.orderpilot.application.services.runtime.RateLimitStore;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoreConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(SecretVaultService.class)
  SecretVaultService secretVaultService(Clock clock) {
    return new LocalDevelopmentSecretVaultService(clock);
  }

  // OP-CAP-16C: in-process rate-limit store by default. A distributed (e.g. Redis) RateLimitStore
  // bean, if introduced later, replaces this without changing RateLimitService.
  @Bean
  @ConditionalOnMissingBean(RateLimitStore.class)
  RateLimitStore rateLimitStore() {
    return new InMemoryRateLimitStore();
  }
}
