package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

class ProductionAuthenticationReadinessGuardTest {

  private ApplicationContextRunner runnerWithProfiles(String... profiles) {
    return new ApplicationContextRunner()
        .withInitializer((ConfigurableApplicationContext ctx) -> ctx.getEnvironment().setActiveProfiles(profiles))
        .withUserConfiguration(ProductionAuthenticationReadinessGuard.class);
  }

  @Test
  void productionProfileRejectsOidcEnabledFlag() {
    runnerWithProfiles("prod")
        .withPropertyValues("orderpilot.security.oidc.enabled=true")
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OIDC_RUNTIME_NOT_IMPLEMENTED")
            .hasMessageNotContaining("client-secret"));
  }

  @Test
  void testProfileAllowsOidcFlagBecauseGuardIsProductionOnly() {
    runnerWithProfiles("test")
        .withPropertyValues("orderpilot.security.oidc.enabled=true")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
