package com.orderpilot.security.production;

import com.orderpilot.infrastructure.config.ProductionDeploymentProperties;
import com.orderpilot.security.ControlPlaneCredentialConfigurationValidator;
import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * P1-A typed production configuration validation: demo authority, signed gateway trust, non-placeholder
 * secrets, and bounded URL/port/timeout/limit checks. Runs only for production-like Spring profiles.
 */
@Component
public class ProductionConfigurationValidator implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(ProductionConfigurationValidator.class);
  static final String OIDC_RUNTIME_NOT_IMPLEMENTED = "OIDC_RUNTIME_NOT_IMPLEMENTED";
  private static final int MAX_PORT = 65535;
  private static final long MAX_CLOCK_SKEW_SECONDS = 86_400L;
  // WP-3: native dependency-acquisition timeouts must stay at or below the control-plane aggregate
  // dependency probe deadline (ControlPlaneStatusService default 6s), so the driver-level bound always
  // fires before the aggregate probe out-waits it.
  private static final long CONTROL_PROBE_DEADLINE_MS = 6_000L;

  private final Environment environment;
  private final ProductionDeploymentProperties deploymentProperties;
  private final boolean gatewayHeaderAuthEnabled;
  private final boolean gatewaySignatureRequired;
  private final String gatewaySharedSecret;
  private final String gatewayReplayStore;
  private final boolean singleInstanceReplayStoreAllowed;
  private final long gatewayClockSkewSeconds;
  private final String actorSigningSecret;
  private final long actorSignatureMaxSkewSeconds;
  private final boolean demoRfqHandoffEnabled;
  private final boolean oidcEnabled;
  private final String corsAllowedOrigins;
  private final String datasourcePassword;
  private final int serverPort;
  private final long malwareScanConnectTimeoutMs;
  private final long malwareScanReadTimeoutMs;
  private final long runtimeControlMaxCostPerRequest;
  private final long runtimeControlDailyCostPerTenant;
  private final int orderJourneyDrainBatchSize;
  private final int orderJourneyDrainMaxTenants;
  private final long orderJourneyDrainFixedDelayMs;
  private final String runtimeRateStore;
  private final String malwareScanMode;
  private final String controlPlaneCredentialAlias;
  private final String controlPlaneSharedSecret;
  private final String controlPlaneAudience;
  private final String controlPlaneStatus;
  private final String controlPlaneValidFrom;
  private final String controlPlaneExpiresAt;
  private final boolean controlPlaneRevoked;
  private final String controlPlanePermissions;
  private final String controlPlaneKeyVersion;
  private final long dbConnectionTimeoutMs;
  private final long redisConnectTimeoutMs;
  private final long redisCommandTimeoutMs;
  private final Clock clock;

  public ProductionConfigurationValidator(
      Environment environment,
      ProductionDeploymentProperties deploymentProperties,
      @Value("${orderpilot.security.gateway-header-auth.enabled:false}") boolean gatewayHeaderAuthEnabled,
      @Value("${orderpilot.security.gateway-header-auth.signature-required:true}") boolean gatewaySignatureRequired,
      @Value("${orderpilot.security.gateway-header-auth.shared-secret:}") String gatewaySharedSecret,
      @Value("${orderpilot.security.gateway-header-auth.replay-store:memory}") String gatewayReplayStore,
      @Value("${orderpilot.security.gateway-header-auth.allow-single-instance-replay-store-in-production:false}")
          boolean singleInstanceReplayStoreAllowed,
      @Value("${orderpilot.security.gateway-header-auth.clock-skew-seconds:300}")
          long gatewayClockSkewSeconds,
      @Value("${orderpilot.security.actor-signing-secret:}") String actorSigningSecret,
      @Value("${orderpilot.security.actor-signature-max-skew-seconds:300}")
          long actorSignatureMaxSkewSeconds,
      @Value("${orderpilot.demo.rfq-handoff.enabled:false}") boolean demoRfqHandoffEnabled,
      @Value("${orderpilot.security.oidc.enabled:false}") boolean oidcEnabled,
      @Value("${orderpilot.security.cors.allowed-origins:}") String corsAllowedOrigins,
      @Value("${spring.datasource.password:}") String datasourcePassword,
      @Value("${server.port:8080}") int serverPort,
      @Value("${orderpilot.intake.malware-scan.connect-timeout-ms:2000}") long malwareScanConnectTimeoutMs,
      @Value("${orderpilot.intake.malware-scan.read-timeout-ms:5000}") long malwareScanReadTimeoutMs,
      @Value("${orderpilot.runtime-control.default-max-cost-units-per-request:10000}")
          long runtimeControlMaxCostPerRequest,
      @Value("${orderpilot.runtime-control.default-daily-cost-units-per-tenant:100000}")
          long runtimeControlDailyCostPerTenant,
      @Value("${orderpilot.runtime.order-journey-projection.batch-size:25}") int orderJourneyDrainBatchSize,
      @Value("${orderpilot.runtime.order-journey-projection.max-tenants-per-cycle:10}")
          int orderJourneyDrainMaxTenants,
      @Value("${orderpilot.runtime.order-journey-projection.fixed-delay-ms:30000}")
          long orderJourneyDrainFixedDelayMs,
      @Value("${orderpilot.runtime.rate.store:in-memory}") String runtimeRateStore,
      @Value("${orderpilot.intake.malware-scan.mode:pass-through}") String malwareScanMode,
      @Value("${orderpilot.security.control-plane-auth.credential-alias:}") String controlPlaneCredentialAlias,
      @Value("${orderpilot.security.control-plane-auth.shared-secret:}") String controlPlaneSharedSecret,
      @Value("${orderpilot.security.control-plane-auth.audience:}") String controlPlaneAudience,
      @Value("${orderpilot.security.control-plane-auth.status:DISABLED}") String controlPlaneStatus,
      @Value("${orderpilot.security.control-plane-auth.valid-from:}") String controlPlaneValidFrom,
      @Value("${orderpilot.security.control-plane-auth.expires-at:}") String controlPlaneExpiresAt,
      @Value("${orderpilot.security.control-plane-auth.revoked:false}") boolean controlPlaneRevoked,
      @Value("${orderpilot.security.control-plane-auth.permissions:}") String controlPlanePermissions,
      @Value("${orderpilot.security.control-plane-auth.key-version:}") String controlPlaneKeyVersion,
      @Value("${spring.datasource.hikari.connection-timeout:5000}") long dbConnectionTimeoutMs,
      @Value("${orderpilot.security.gateway-header-auth.redis.connect-timeout-ms:2000}") long redisConnectTimeoutMs,
      @Value("${orderpilot.security.gateway-header-auth.redis.command-timeout-ms:2000}") long redisCommandTimeoutMs,
      Clock clock) {
    this.environment = environment;
    this.deploymentProperties = deploymentProperties;
    this.gatewayHeaderAuthEnabled = gatewayHeaderAuthEnabled;
    this.gatewaySignatureRequired = gatewaySignatureRequired;
    this.gatewaySharedSecret = gatewaySharedSecret;
    this.gatewayReplayStore = gatewayReplayStore;
    this.singleInstanceReplayStoreAllowed = singleInstanceReplayStoreAllowed;
    this.gatewayClockSkewSeconds = gatewayClockSkewSeconds;
    this.actorSigningSecret = actorSigningSecret;
    this.actorSignatureMaxSkewSeconds = actorSignatureMaxSkewSeconds;
    this.demoRfqHandoffEnabled = demoRfqHandoffEnabled;
    this.oidcEnabled = oidcEnabled;
    this.corsAllowedOrigins = corsAllowedOrigins;
    this.datasourcePassword = datasourcePassword;
    this.serverPort = serverPort;
    this.malwareScanConnectTimeoutMs = malwareScanConnectTimeoutMs;
    this.malwareScanReadTimeoutMs = malwareScanReadTimeoutMs;
    this.runtimeControlMaxCostPerRequest = runtimeControlMaxCostPerRequest;
    this.runtimeControlDailyCostPerTenant = runtimeControlDailyCostPerTenant;
    this.orderJourneyDrainBatchSize = orderJourneyDrainBatchSize;
    this.orderJourneyDrainMaxTenants = orderJourneyDrainMaxTenants;
    this.orderJourneyDrainFixedDelayMs = orderJourneyDrainFixedDelayMs;
    this.runtimeRateStore = runtimeRateStore;
    this.malwareScanMode = malwareScanMode;
    this.controlPlaneCredentialAlias = controlPlaneCredentialAlias;
    this.controlPlaneSharedSecret = controlPlaneSharedSecret;
    this.controlPlaneAudience = controlPlaneAudience;
    this.controlPlaneStatus = controlPlaneStatus;
    this.controlPlaneValidFrom = controlPlaneValidFrom;
    this.controlPlaneExpiresAt = controlPlaneExpiresAt;
    this.controlPlaneRevoked = controlPlaneRevoked;
    this.controlPlanePermissions = controlPlanePermissions;
    this.controlPlaneKeyVersion = controlPlaneKeyVersion;
    this.dbConnectionTimeoutMs = dbConnectionTimeoutMs;
    this.redisConnectTimeoutMs = redisConnectTimeoutMs;
    this.redisCommandTimeoutMs = redisCommandTimeoutMs;
    this.clock = clock;
  }

  @Override
  public void afterPropertiesSet() {
    if (!ProductionLikeProfiles.isActive(environment)) {
      return;
    }
    rejectDemoAuthority();
    rejectUnsignedGatewayTrust();
    validateControlPlaneCredential();
    rejectPlaceholderSecrets();
    validateRequiredUrlsAndOrigins();
    validatePortsTimeoutsAndLimits();
    validateDependencyProbeNativeTimeouts();
    emitRedactedDiagnostics();
  }

  private void rejectDemoAuthority() {
    if (demoRfqHandoffEnabled) {
      throw new IllegalStateException(
          "orderpilot.demo.rfq-handoff.enabled must be false in production-like profiles");
    }
    if (oidcEnabled) {
      throw new IllegalStateException(
          OIDC_RUNTIME_NOT_IMPLEMENTED
              + ": orderpilot.security.oidc.enabled=true cannot enable production authentication "
              + "until the BFF OIDC runtime is implemented");
    }
  }

  private void rejectUnsignedGatewayTrust() {
    GatewayHeaderAuthProductionRules.validateProductionGatewayConfiguration(
        gatewayHeaderAuthEnabled,
        gatewaySignatureRequired,
        gatewaySharedSecret,
        gatewayReplayStore,
        singleInstanceReplayStoreAllowed);
  }

  private void validateControlPlaneCredential() {
    ControlPlaneCredentialConfigurationValidator.validateProductionConfiguration(
        controlPlaneCredentialAlias,
        controlPlaneSharedSecret,
        controlPlaneAudience,
        controlPlaneStatus,
        controlPlaneValidFrom,
        controlPlaneExpiresAt,
        controlPlaneRevoked,
        controlPlanePermissions,
        controlPlaneKeyVersion,
        gatewaySharedSecret,
        clock);
  }

  private void rejectPlaceholderSecrets() {
    ProductionInsecurePlaceholderValues.requireNonPlaceholder(
        "orderpilot.security.actor-signing-secret", actorSigningSecret);
    ProductionInsecurePlaceholderValues.requireNonPlaceholder("spring.datasource.password", datasourcePassword);
  }

  private void validateRequiredUrlsAndOrigins() {
    requireHttpsBaseUrl(
        "orderpilot.production.public-api-base-url", deploymentProperties.getPublicApiBaseUrl());
    requireHttpsBaseUrl(
        "orderpilot.production.public-web-base-url", deploymentProperties.getPublicWebBaseUrl());
    if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
      throw new IllegalStateException(
          "orderpilot.security.cors.allowed-origins must be configured in production-like profiles");
    }
    for (String origin : corsAllowedOrigins.split(",")) {
      String trimmed = origin.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      requireHttpOrigin("orderpilot.security.cors.allowed-origins", trimmed);
    }
  }

  private void validatePortsTimeoutsAndLimits() {
    requirePortInRange("server.port", serverPort);
    requirePositive("orderpilot.security.gateway-header-auth.clock-skew-seconds", gatewayClockSkewSeconds);
    requireMax("orderpilot.security.gateway-header-auth.clock-skew-seconds", gatewayClockSkewSeconds, MAX_CLOCK_SKEW_SECONDS);
    requirePositive(
        "orderpilot.security.actor-signature-max-skew-seconds", actorSignatureMaxSkewSeconds);
    requireMax(
        "orderpilot.security.actor-signature-max-skew-seconds",
        actorSignatureMaxSkewSeconds,
        MAX_CLOCK_SKEW_SECONDS);
    requirePositive(
        "orderpilot.intake.malware-scan.connect-timeout-ms", malwareScanConnectTimeoutMs);
    requirePositive("orderpilot.intake.malware-scan.read-timeout-ms", malwareScanReadTimeoutMs);
    requirePositive(
        "orderpilot.runtime-control.default-max-cost-units-per-request",
        runtimeControlMaxCostPerRequest);
    requirePositive(
        "orderpilot.runtime-control.default-daily-cost-units-per-tenant",
        runtimeControlDailyCostPerTenant);
    requirePositive("orderpilot.runtime.order-journey-projection.batch-size", orderJourneyDrainBatchSize);
    requirePositive(
        "orderpilot.runtime.order-journey-projection.max-tenants-per-cycle", orderJourneyDrainMaxTenants);
    requirePositive(
        "orderpilot.runtime.order-journey-projection.fixed-delay-ms", orderJourneyDrainFixedDelayMs);
    String rateStore =
        runtimeRateStore == null ? "" : runtimeRateStore.trim().toLowerCase(Locale.ROOT);
    if (!"redis".equals(rateStore) && !"in-memory".equals(rateStore)) {
      throw new IllegalStateException(
          "orderpilot.runtime.rate.store must be redis or in-memory in production-like profiles");
    }
    if ("in-memory".equals(rateStore)) {
      log.warn(
          "orderpilot.runtime.rate.store=in-memory in a production-like profile is single-instance only");
    }
  }

  private void validateDependencyProbeNativeTimeouts() {
    // WP-3: database connection acquisition and Redis connect/command must be bounded natively (not by
    // library defaults) and the DB acquisition bound must not exceed the aggregate control probe budget.
    requirePositive("spring.datasource.hikari.connection-timeout", dbConnectionTimeoutMs);
    requireMax("spring.datasource.hikari.connection-timeout", dbConnectionTimeoutMs, CONTROL_PROBE_DEADLINE_MS);
    requirePositive(
        "orderpilot.security.gateway-header-auth.redis.connect-timeout-ms", redisConnectTimeoutMs);
    requireMax(
        "orderpilot.security.gateway-header-auth.redis.connect-timeout-ms",
        redisConnectTimeoutMs,
        CONTROL_PROBE_DEADLINE_MS);
    requirePositive(
        "orderpilot.security.gateway-header-auth.redis.command-timeout-ms", redisCommandTimeoutMs);
    requireMax(
        "orderpilot.security.gateway-header-auth.redis.command-timeout-ms",
        redisCommandTimeoutMs,
        CONTROL_PROBE_DEADLINE_MS);
  }

  private void emitRedactedDiagnostics() {
    if (!deploymentProperties.isEmitDiagnostics()) {
      return;
    }
    var snapshot =
        ProductionConfigurationDiagnostics.redactedSnapshot(
            gatewayHeaderAuthEnabled,
            gatewaySignatureRequired,
            gatewayReplayStore,
            demoRfqHandoffEnabled,
            oidcEnabled,
            deploymentProperties.getPublicApiBaseUrl(),
            deploymentProperties.getPublicWebBaseUrl(),
            corsAllowedOrigins,
            serverPort,
            malwareScanMode,
            runtimeRateStore);
    log.info(ProductionConfigurationDiagnostics.formatForLog(snapshot));
  }

  static void requireHttpsBaseUrl(String propertyName, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(propertyName + " must be configured in production-like profiles");
    }
    URI uri = parseUri(propertyName, value.trim());
    String scheme = uri.getScheme();
    if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
      throw new IllegalStateException(propertyName + " must use https in production-like profiles");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalStateException(propertyName + " must include a host");
    }
    if (uri.getRawPath() != null && !uri.getRawPath().isBlank() && !"/".equals(uri.getRawPath())) {
      throw new IllegalStateException(propertyName + " must not include a path (base URL only)");
    }
  }

  static void requireHttpOrigin(String propertyName, String value) {
    URI uri = parseUri(propertyName, value);
    String scheme = uri.getScheme();
    if (scheme == null
        || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
      throw new IllegalStateException(propertyName + " entry must be an http(s) origin: " + value);
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalStateException(propertyName + " entry must include a host: " + value);
    }
  }

  private static URI parseUri(String propertyName, String value) {
    try {
      return URI.create(value);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(propertyName + " is not a valid URL: " + value, ex);
    }
  }

  static void requirePortInRange(String propertyName, int port) {
    if (port < 1 || port > MAX_PORT) {
      throw new IllegalStateException(
          propertyName + " must be between 1 and " + MAX_PORT + " in production-like profiles");
    }
  }

  static void requirePositive(String propertyName, long value) {
    if (value <= 0) {
      throw new IllegalStateException(propertyName + " must be > 0 in production-like profiles");
    }
  }

  static void requireMax(String propertyName, long value, long maxInclusive) {
    if (value > maxInclusive) {
      throw new IllegalStateException(
          propertyName + " must be <= " + maxInclusive + " in production-like profiles");
    }
  }
}