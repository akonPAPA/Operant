package com.orderpilot.infrastructure.config;

import com.orderpilot.application.services.connector.LocalDevelopmentSecretVaultService;
import com.orderpilot.application.services.connector.SecretVaultService;
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
}
