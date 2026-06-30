package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

class ProductionIntakeSecurityGuardTest {

  private ApplicationContextRunner runnerWithProfiles(String... profiles) {
    return new ApplicationContextRunner()
        .withInitializer((ConfigurableApplicationContext ctx) -> ctx.getEnvironment().setActiveProfiles(profiles))
        .withUserConfiguration(ProductionIntakeSecurityGuard.class);
  }

  @Test
  void productionProfileRejectsPassThroughMalwareScanByDefault() {
    runnerWithProfiles("prod")
        .withPropertyValues("orderpilot.intake.malware-scan.mode=pass-through")
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("mode must be external"));
  }

  @Test
  void productionProfileRejectsExternalModeWithoutEndpoint() {
    runnerWithProfiles("prod")
        .withPropertyValues(
            "orderpilot.intake.malware-scan.mode=external",
            "orderpilot.intake.malware-scan.external-endpoint=")
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("external-endpoint must be configured"));
  }

  @Test
  void productionProfileAcceptsExternalModeWithEndpoint() {
    runnerWithProfiles("production")
        .withPropertyValues(
            "orderpilot.intake.malware-scan.mode=external",
            "orderpilot.intake.malware-scan.external-endpoint=https://scanner.example.test/scan")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void productionProfileAllowsExplicitPassThroughOptIn() {
    runnerWithProfiles("production")
        .withPropertyValues(
            "orderpilot.intake.malware-scan.mode=pass-through",
            "orderpilot.intake.malware-scan.allow-pass-through-in-production=true")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void testProfileDoesNotEnforceMalwareScanGuard() {
    runnerWithProfiles("test").run(context -> assertThat(context).hasNotFailed());
  }
}
