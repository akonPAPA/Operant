package com.orderpilot.security.production;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.infrastructure.config.ProductionDeploymentProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

class ProductionConfigurationValidatorTest {

  private static final String GATEWAY_SECRET = "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517";
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
  static class PropertiesHarness {}

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
