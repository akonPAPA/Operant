package com.orderpilot.security.production;

import com.orderpilot.security.ControlPlaneCredentialConfigurationValidator;
import com.orderpilot.security.ControlPlanePrincipalType;
import java.time.Clock;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Strict production-like validation for both independent control-plane credential slots.
 *
 * <p>Enabled production credentials must configure an explicit stable logical principal id. Falling back
 * to the credential alias would make an alias rotation silently change durable lease ownership.
 */
@Component
public class ControlPlaneCredentialSetProductionValidator implements InitializingBean {
  private static final String STAFF_PREFIX = "orderpilot.security.control-plane-auth";
  private static final String EXECUTOR_PREFIX = "orderpilot.security.control-plane-auth.executor";

  private final Environment environment;
  private final Clock clock;
  private final String gatewaySharedSecret;

  private final String staffPrincipalId;
  private final String staffAlias;
  private final String staffSharedSecret;
  private final String staffAudience;
  private final String staffStatus;
  private final String staffValidFrom;
  private final String staffExpiresAt;
  private final boolean staffRevoked;
  private final String staffPermissions;
  private final String staffKeyVersion;

  private final String executorPrincipalId;
  private final String executorAlias;
  private final String executorSharedSecret;
  private final String executorAudience;
  private final String executorStatus;
  private final String executorValidFrom;
  private final String executorExpiresAt;
  private final boolean executorRevoked;
  private final String executorPermissions;
  private final String executorKeyVersion;

  public ControlPlaneCredentialSetProductionValidator(
      Environment environment,
      Clock clock,
      @Value("${orderpilot.security.gateway-header-auth.shared-secret:}") String gatewaySharedSecret,
      @Value("${orderpilot.security.control-plane-auth.principal-id:}") String staffPrincipalId,
      @Value("${orderpilot.security.control-plane-auth.credential-alias:}") String staffAlias,
      @Value("${orderpilot.security.control-plane-auth.shared-secret:}") String staffSharedSecret,
      @Value("${orderpilot.security.control-plane-auth.audience:}") String staffAudience,
      @Value("${orderpilot.security.control-plane-auth.status:DISABLED}") String staffStatus,
      @Value("${orderpilot.security.control-plane-auth.valid-from:}") String staffValidFrom,
      @Value("${orderpilot.security.control-plane-auth.expires-at:}") String staffExpiresAt,
      @Value("${orderpilot.security.control-plane-auth.revoked:false}") boolean staffRevoked,
      @Value("${orderpilot.security.control-plane-auth.permissions:}") String staffPermissions,
      @Value("${orderpilot.security.control-plane-auth.key-version:}") String staffKeyVersion,
      @Value("${orderpilot.security.control-plane-auth.executor.principal-id:}") String executorPrincipalId,
      @Value("${orderpilot.security.control-plane-auth.executor.credential-alias:}") String executorAlias,
      @Value("${orderpilot.security.control-plane-auth.executor.shared-secret:}") String executorSharedSecret,
      @Value("${orderpilot.security.control-plane-auth.executor.audience:}") String executorAudience,
      @Value("${orderpilot.security.control-plane-auth.executor.status:DISABLED}") String executorStatus,
      @Value("${orderpilot.security.control-plane-auth.executor.valid-from:}") String executorValidFrom,
      @Value("${orderpilot.security.control-plane-auth.executor.expires-at:}") String executorExpiresAt,
      @Value("${orderpilot.security.control-plane-auth.executor.revoked:false}") boolean executorRevoked,
      @Value("${orderpilot.security.control-plane-auth.executor.permissions:}") String executorPermissions,
      @Value("${orderpilot.security.control-plane-auth.executor.key-version:}") String executorKeyVersion) {
    this.environment = environment;
    this.clock = clock;
    this.gatewaySharedSecret = gatewaySharedSecret;
    this.staffPrincipalId = staffPrincipalId;
    this.staffAlias = staffAlias;
    this.staffSharedSecret = staffSharedSecret;
    this.staffAudience = staffAudience;
    this.staffStatus = staffStatus;
    this.staffValidFrom = staffValidFrom;
    this.staffExpiresAt = staffExpiresAt;
    this.staffRevoked = staffRevoked;
    this.staffPermissions = staffPermissions;
    this.staffKeyVersion = staffKeyVersion;
    this.executorPrincipalId = executorPrincipalId;
    this.executorAlias = executorAlias;
    this.executorSharedSecret = executorSharedSecret;
    this.executorAudience = executorAudience;
    this.executorStatus = executorStatus;
    this.executorValidFrom = executorValidFrom;
    this.executorExpiresAt = executorExpiresAt;
    this.executorRevoked = executorRevoked;
    this.executorPermissions = executorPermissions;
    this.executorKeyVersion = executorKeyVersion;
  }

  @Override
  public void afterPropertiesSet() {
    if (!ProductionLikeProfiles.isActive(environment)) {
      return;
    }
    ControlPlaneCredentialConfigurationValidator.validateProductionCredentialSlot(
        STAFF_PREFIX,
        staffPrincipalId,
        ControlPlanePrincipalType.OPERANT_STAFF,
        staffAlias,
        staffSharedSecret,
        staffAudience,
        staffStatus,
        staffValidFrom,
        staffExpiresAt,
        staffRevoked,
        staffPermissions,
        staffKeyVersion,
        gatewaySharedSecret,
        clock);
    ControlPlaneCredentialConfigurationValidator.validateProductionCredentialSlot(
        EXECUTOR_PREFIX,
        executorPrincipalId,
        ControlPlanePrincipalType.LIFECYCLE_EXECUTOR,
        executorAlias,
        executorSharedSecret,
        executorAudience,
        executorStatus,
        executorValidFrom,
        executorExpiresAt,
        executorRevoked,
        executorPermissions,
        executorKeyVersion,
        gatewaySharedSecret,
        clock);
  }
}
