package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * OP-CAP-43D/43F - Production profile guard for gateway-header authentication.
 *
 * <p>Uses {@link ApplicationContextRunner} so each case is a tiny refresh of only the
 * {@link GatewayHeaderAuthProductionGuard} bean. The shared secret values here are test-only
 * non-secrets used purely to exercise the blank/non-blank branches.
 */
class GatewayHeaderAuthProductionGuardTest {

  private static final String TEST_ONLY_SECRET = "p1a-gateway-guard-test-secret-not-placeholder";

  private ApplicationContextRunner runnerWithProfiles(String... profiles) {
    return new ApplicationContextRunner()
        .withInitializer((ConfigurableApplicationContext ctx) -> ctx.getEnvironment().setActiveProfiles(profiles))
        .withUserConfiguration(GatewayHeaderAuthProductionGuard.class);
  }

  @Test
  void productionProfileRejectsGatewayHeaderAuthWithSignatureRequiredFalse() {
    runnerWithProfiles("prod")
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=true",
            "orderpilot.security.gateway-header-auth.signature-required=false",
            "orderpilot.security.gateway-header-auth.shared-secret=" + TEST_ONLY_SECRET)
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("signature-required must be true in production"));
  }

  @Test
  void productionProfileRejectsGatewayHeaderAuthWithBlankSharedSecret() {
    runnerWithProfiles("production")
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=true",
            "orderpilot.security.gateway-header-auth.signature-required=true",
            "orderpilot.security.gateway-header-auth.shared-secret=",
            "orderpilot.security.gateway-header-auth.replay-store=redis")
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("shared-secret must be configured in production"));
  }

  @Test
  void productionProfileRequiresDistributedReplayStoreWhenSignedHeaderAuthEnabled() {
    runnerWithProfiles("prod")
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=true",
            "orderpilot.security.gateway-header-auth.signature-required=true",
            "orderpilot.security.gateway-header-auth.shared-secret=" + TEST_ONLY_SECRET)
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("replay-store must be redis in production signed mode"));
  }

  @Test
  void productionProfileRejectsPlaceholderSharedSecret() {
    runnerWithProfiles("prod")
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=true",
            "orderpilot.security.gateway-header-auth.signature-required=true",
            "orderpilot.security.gateway-header-auth.shared-secret=change-me-local-dev-only",
            "orderpilot.security.gateway-header-auth.replay-store=redis")
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("non-placeholder secret"));
  }

  @Test
  void productionProfileAcceptsGatewayHeaderAuthWithSignatureRequiredSecretAndRedisReplayStore() {
    runnerWithProfiles("prod")
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=true",
            "orderpilot.security.gateway-header-auth.signature-required=true",
            "orderpilot.security.gateway-header-auth.shared-secret=" + TEST_ONLY_SECRET,
            "orderpilot.security.gateway-header-auth.replay-store=redis")
        .run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(GatewayHeaderAuthProductionGuard.class));
  }

  @Test
  void productionProfileAllowsExplicitSingleInstanceReplayStoreOverride() {
    runnerWithProfiles("prod")
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=true",
            "orderpilot.security.gateway-header-auth.signature-required=true",
            "orderpilot.security.gateway-header-auth.shared-secret=" + TEST_ONLY_SECRET,
            "orderpilot.security.gateway-header-auth.allow-single-instance-replay-store-in-production=true")
        .run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(GatewayHeaderAuthProductionGuard.class));
  }

  @Test
  void productionProfileRejectsGatewayHeaderAuthDisabled() {
    runnerWithProfiles("prod")
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=false",
            "orderpilot.security.gateway-header-auth.signature-required=false",
            "orderpilot.security.gateway-header-auth.shared-secret=")
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("gateway-header-auth must be enabled in production"));
  }

  @Test
  void stagingProfileRejectsUnsignedGatewayHeaderMode() {
    runnerWithProfiles("staging")
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=true",
            "orderpilot.security.gateway-header-auth.signature-required=false")
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("signature-required must be true in production"));
  }

  @Test
  void testProfileStillAllowsUnsignedGatewayHeaderMode() {
    runnerWithProfiles("test")
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=true",
            "orderpilot.security.gateway-header-auth.signature-required=false")
        .run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(GatewayHeaderAuthProductionGuard.class));
  }

  @Test
  void defaultProfileAllowsUnsignedGatewayHeaderMode() {
    new ApplicationContextRunner()
        .withUserConfiguration(GatewayHeaderAuthProductionGuard.class)
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=true",
            "orderpilot.security.gateway-header-auth.signature-required=false")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void guardFailureDoesNotLeakSecret() {
    runnerWithProfiles("prod")
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=true",
            "orderpilot.security.gateway-header-auth.signature-required=false",
            "orderpilot.security.gateway-header-auth.shared-secret=" + TEST_ONLY_SECRET)
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .hasMessageNotContaining(TEST_ONLY_SECRET));
  }
}
