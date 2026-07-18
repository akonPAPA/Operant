package com.orderpilot.security.production;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.infrastructure.config.ProductionDeploymentProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ProductionConfigurationValidatorTest {

  private static final String GATEWAY_SECRET = "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517";
  private static final String CONTROL_SECRET = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  private static final String ACTOR_SECRET = "p1a-actor-signing-secret-value-32";
  private static final String DB_PASSWORD = "p1a-database-password-value-32";

  private ApplicationContextRunner productionRunner() {
    return new ApplicationContextRunner()
        .withInitializer(
            (ConfigurableApplicationContext ctx) -> ctx.getEnvironment().setActiveProfiles("prod"))
        .withUserConfiguration(ProductionConfigurationValidator.class, PropertiesHarness.class);
  }

  @Configuration
  @EnableConfigurationProperties(ProductionDeploymentProperties.class)
  static class PropertiesHarness {
    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);
    }
  }

  private String[] validProductionProperties() {
    return new String[] {
      "orderpilot.security.gateway-header-auth.enabled=true",
      "orderpilot.security.gateway-header-auth.signature-required=true",
      "orderpilot.security.gateway-header-auth.shared-secret=" + GATEWAY_SECRET,
      "orderpilot.security.gateway-header-auth.replay-store=redis",
      "orderpilot.security.actor-signing-secret=" + ACTOR_SECRET,
      "spring.datasource.password=" + DB_PASSWORD,
      "orderpilot.production.public-api-base-url=https://api.example.test",
      "orderpilot.production.public-web-base-url=https://app.example.test",
      "orderpilot.security.cors.allowed-origins=https://app.example.test",
      "orderpilot.demo.rfq-handoff.enabled=false",
      "orderpilot.security.oidc.enabled=false",
      "orderpilot.production.emit-diagnostics=false"
    };
  }

  private String[] validEnabledControlCredentialProperties() {
    return new String[] {
      "orderpilot.security.control-plane-auth.credential-alias=ops-prod",
      "orderpilot.security.control-plane-auth.shared-secret=" + CONTROL_SECRET,
      "orderpilot.security.control-plane-auth.audience=orderpilot-control-plane",
      "orderpilot.security.control-plane-auth.status=ENABLED",
      "orderpilot.security.control-plane-auth.valid-from=2026-01-01T00:00:00Z",
      "orderpilot.security.control-plane-auth.expires-at=2099-01-01T00:00:00Z",
      "orderpilot.security.control-plane-auth.revoked=false",
      "orderpilot.security.control-plane-auth.permissions=STAFF_CONTROL_READ",
      "orderpilot.security.control-plane-auth.key-version=control-v1"
    };
  }

  @Test
  void productionProfileAllowsDisabledBlankControlCredential() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues(
            "orderpilot.security.control-plane-auth.status=DISABLED",
            "orderpilot.security.control-plane-auth.credential-alias=",
            "orderpilot.security.control-plane-auth.shared-secret=",
            "orderpilot.security.control-plane-auth.audience=",
            "orderpilot.security.control-plane-auth.valid-from=",
            "orderpilot.security.control-plane-auth.expires-at=",
            "orderpilot.security.control-plane-auth.permissions=",
            "orderpilot.security.control-plane-auth.key-version=")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void productionProfileAcceptsExplicitValidEnabledControlCredential() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues(validEnabledControlCredentialProperties())
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void productionProfileRejectsEnabledControlCredentialInvalidProperties() {
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.credential-alias=", "credential-alias");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.shared-secret=", "shared-secret");
    assertInvalidControlProperty(
        "orderpilot.security.control-plane-auth.shared-secret=0000000000000000000000000000000000000000000000000000000000000000",
        "shared-secret");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.shared-secret=" + GATEWAY_SECRET, "shared-secret");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.valid-from=", "valid-from");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.valid-from=not-an-instant", "valid-from");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.expires-at=", "expires-at");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.expires-at=not-an-instant", "expires-at");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.expires-at=2026-01-01T00:00:00Z", "expires-at");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.expires-at=2026-01-01T00:00:00Z", "expires-at");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.permissions=", "permissions");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.permissions=UNKNOWN", "permissions");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.permissions=ADMIN_SETTINGS_READ", "permissions");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.status=UNKNOWN", "status");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.audience=other-audience", "audience");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.key-version=", "key-version");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.key-version=bad version", "key-version");
    assertInvalidControlProperty("orderpilot.security.control-plane-auth.revoked=true", "revoked");
  }

  private void assertInvalidControlProperty(String propertyOverride, String propertyName) {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues(validEnabledControlCredentialProperties())
        .withPropertyValues(propertyOverride)
        .run(context -> assertControlFailure(context, propertyName));
  }

  private static void assertControlFailure(org.springframework.boot.test.context.assertj.AssertableApplicationContext context,
      String propertyName) {
    assertThat(context)
        .hasFailed()
        .getFailure()
        .rootCause()
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("control-plane-auth." + propertyName)
        .hasMessageNotContaining(GATEWAY_SECRET)
        .hasMessageNotContaining(CONTROL_SECRET);
  }

  @Test
  void productionProfileAcceptsValidProductionLikeConfiguration() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ProductionConfigurationValidator.class));
  }

  @Test
  void productionProfileRejectsDemoRfqHandoffEnabled() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.demo.rfq-handoff.enabled=true")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("demo.rfq-handoff.enabled must be false"));
  }

  @Test
  void productionProfileRejectsOidcEnabledBecauseRuntimeIsNotImplemented() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.security.oidc.enabled=true")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OIDC_RUNTIME_NOT_IMPLEMENTED")
                    .hasMessageNotContaining(GATEWAY_SECRET)
                    .hasMessageNotContaining(ACTOR_SECRET)
                    .hasMessageNotContaining(DB_PASSWORD));
  }

  @Test
  void productionProfileRejectsUnsignedGatewayTrust() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.security.gateway-header-auth.signature-required=false")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("signature-required must be true"));
  }

  @Test
  void productionProfileRejectsDisabledGatewayHeaderAuth() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.security.gateway-header-auth.enabled=false")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("gateway-header-auth must be enabled"));
  }

  @Test
  void productionProfileRejectsBlankGatewayHmacKeyCodec() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.security.gateway-header-auth.shared-secret=")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("64 hexadecimal characters")
                    .hasMessageNotContaining(GATEWAY_SECRET));
  }

  @Test
  void productionProfileRejectsPlaceholderGatewayHmacKeyCodec() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.security.gateway-header-auth.shared-secret=change-me-local-dev-only")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("64 hexadecimal characters")
                    .hasMessageNotContaining("change-me-local-dev-only"));
  }

  @Test
  void productionProfileRejectsInvalidGatewayReplayStore() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.security.gateway-header-auth.replay-store=memory")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("replay-store must be redis"));
  }

  @Test
  void productionProfileRejectsBlankActorSigningSecret() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.security.actor-signing-secret=")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("actor-signing-secret")
                    .hasMessageNotContaining(ACTOR_SECRET));
  }

  @Test
  void productionProfileRejectsPlaceholderDatasourcePassword() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("spring.datasource.password=change-me-local-dev-only")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.datasource.password")
                    .hasMessageNotContaining("change-me-local-dev-only"));
  }

  @Test
  void productionProfileRejectsInvalidCorsOrigin() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.security.cors.allowed-origins=not-a-valid-origin")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cors.allowed-origins"));
  }

  @Test
  void productionProfileRejectsNonPositiveMalwareScanTimeout() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.intake.malware-scan.connect-timeout-ms=0")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("connect-timeout-ms"));
  }

  @Test
  void productionProfileRejectsPlaceholderActorSigningSecret() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.security.actor-signing-secret=change-me")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("actor-signing-secret"));
  }

  @Test
  void productionProfileRejectsMissingPublicApiBaseUrl() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.production.public-api-base-url=")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("public-api-base-url"));
  }

  @Test
  void productionProfileRejectsHttpPublicApiBaseUrl() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.production.public-api-base-url=http://api.example.test")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must use https"));
  }

  @Test
  void productionProfileRejectsInvalidServerPort() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("server.port=0")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("server.port"));
  }

  @Test
  void testProfileDoesNotRunProductionValidator() {
    new ApplicationContextRunner()
        .withInitializer(
            (ConfigurableApplicationContext ctx) -> ctx.getEnvironment().setActiveProfiles("test"))
        .withUserConfiguration(ProductionConfigurationValidator.class, PropertiesHarness.class)
        .withPropertyValues("orderpilot.demo.rfq-handoff.enabled=true")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void productionStartupFailureDoesNotLeakConfiguredSecrets() {
    productionRunner()
        .withPropertyValues(validProductionProperties())
        .withPropertyValues("orderpilot.security.gateway-header-auth.signature-required=false")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageNotContaining(GATEWAY_SECRET)
                    .hasMessageNotContaining(ACTOR_SECRET)
                    .hasMessageNotContaining(DB_PASSWORD));
  }

  @Test
  void redactedDiagnosticsDoNotContainSecretValues() {
    Map<String, String> snapshot =
        ProductionConfigurationDiagnostics.redactedSnapshot(
            true,
            true,
            "redis",
            false,
            false,
            "https://api.example.test",
            "https://app.example.test",
            "https://app.example.test",
            8080,
            "external",
            "redis");
    String formatted = ProductionConfigurationDiagnostics.formatForLog(snapshot);
    assertThat(formatted).contains("[redacted]");
    assertThat(formatted).doesNotContain(GATEWAY_SECRET).doesNotContain(ACTOR_SECRET);
  }
}
