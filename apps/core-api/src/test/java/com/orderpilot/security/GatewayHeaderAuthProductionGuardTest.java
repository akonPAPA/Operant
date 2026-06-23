package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * OP-CAP-43D — Production profile guard for gateway-header authentication.
 *
 * <p>OP-CAP-43C ({@link ApiGatewayHeaderAuthenticationHardeningTest},
 * {@link ApiHeaderAuthenticationFilterDisabledModeTest}) proved the running app's HMAC verifier
 * fails closed. This class proves the complementary deploy-time guarantee: a production-like profile
 * must not silently start in the dev/test header-trust shape. The verifier being correct is not
 * enough — production must be configured to actually use it.
 *
 * <p>Uses {@link ApplicationContextRunner} so each case is a tiny, fast context refresh of only the
 * {@link GatewayHeaderAuthProductionGuard} bean. Active profiles are set directly on the environment
 * (deterministic, independent of property-source profile processing). The shared secret values here
 * are test-only non-secrets used purely to exercise the blank/non-blank branches.
 */
class GatewayHeaderAuthProductionGuardTest {

  // Test-only, NOT a real credential. Only used to exercise the non-blank-secret branch and to
  // assert the failure message never echoes the value.
  private static final String TEST_ONLY_SECRET = "op-cap-43d-test-only-non-secret-value";

  private ApplicationContextRunner runnerWithProfiles(String... profiles) {
    return new ApplicationContextRunner()
        .withInitializer((ConfigurableApplicationContext ctx) -> ctx.getEnvironment().setActiveProfiles(profiles))
        .withUserConfiguration(GatewayHeaderAuthProductionGuard.class);
  }

  // 1. Production + enabled + signature-required=false → fail-closed startup, even if a secret is set.
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

  // 2. Production + enabled + signature-required=true but blank shared secret → fail-closed startup.
  @Test
  void productionProfileRejectsGatewayHeaderAuthWithBlankSharedSecret() {
    runnerWithProfiles("production")
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=true",
            "orderpilot.security.gateway-header-auth.signature-required=true",
            "orderpilot.security.gateway-header-auth.shared-secret=")
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("shared-secret must be configured in production"));
  }

  // 3. Production + enabled + signature-required=true + non-blank secret → safe, context starts.
  @Test
  void productionProfileAcceptsGatewayHeaderAuthWithSignatureRequiredAndSecret() {
    runnerWithProfiles("prod")
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=true",
            "orderpilot.security.gateway-header-auth.signature-required=true",
            "orderpilot.security.gateway-header-auth.shared-secret=" + TEST_ONLY_SECRET)
        .run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(GatewayHeaderAuthProductionGuard.class));
  }

  // 4. Production + header trust disabled → context starts: the filter never trusts client headers,
  //    so a blank secret / missing signature config is irrelevant.
  @Test
  void productionProfileAllowsGatewayHeaderAuthDisabled() {
    runnerWithProfiles("prod")
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=false",
            "orderpilot.security.gateway-header-auth.signature-required=false",
            "orderpilot.security.gateway-header-auth.shared-secret=")
        .run(context -> assertThat(context).hasNotFailed());
  }

  // 4b. staging is treated as production-like — same fail-closed contract as prod.
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

  // 5. Non-production test profile may intentionally use the unsigned dev/test convenience mode.
  //    This mirrors src/test/resources/application.yml (enabled=true, signature-required=false).
  //    Dev/test-only behavior — never valid in a production-like profile (see case 1/4b).
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

  // 5b. No active profile (default) is not production-like → unsigned mode is allowed, matching the
  //     existing WebMvcTest slices that run without a production profile.
  @Test
  void defaultProfileAllowsUnsignedGatewayHeaderMode() {
    new ApplicationContextRunner()
        .withUserConfiguration(GatewayHeaderAuthProductionGuard.class)
        .withPropertyValues(
            "orderpilot.security.gateway-header-auth.enabled=true",
            "orderpilot.security.gateway-header-auth.signature-required=false")
        .run(context -> assertThat(context).hasNotFailed());
  }

  // 6. The fail-closed message must never leak the shared-secret value into logs/exceptions.
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
