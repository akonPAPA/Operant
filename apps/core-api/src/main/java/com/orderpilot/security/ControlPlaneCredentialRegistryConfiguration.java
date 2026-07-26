package com.orderpilot.security;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Builds the bounded two-slot control credential registry used by the security filter chain.
 *
 * <p>The original root properties remain the Operant-staff slot for compatibility. The independent
 * {@code executor.*} properties are the service-account slot. Each slot has a fixed principal class; a
 * permission family cannot change the slot's access plane.
 */
@Configuration
public class ControlPlaneCredentialRegistryConfiguration {
  static final String STAFF_PREFIX = "orderpilot.security.control-plane-auth";
  static final String EXECUTOR_PREFIX = "orderpilot.security.control-plane-auth.executor";

  @Bean
  @Primary
  ControlPlaneCredentialRegistry multiPrincipalControlPlaneCredentialRegistry(
      @Value("${orderpilot.security.control-plane-auth.principal-id:${orderpilot.security.control-plane-auth.credential-alias:}}")
          String staffPrincipalId,
      @Value("${orderpilot.security.control-plane-auth.credential-alias:}") String staffAlias,
      @Value("${orderpilot.security.control-plane-auth.shared-secret:}") String staffSharedSecret,
      @Value("${orderpilot.security.control-plane-auth.audience:}") String staffAudience,
      @Value("${orderpilot.security.control-plane-auth.status:DISABLED}") String staffStatus,
      @Value("${orderpilot.security.control-plane-auth.valid-from:}") String staffValidFrom,
      @Value("${orderpilot.security.control-plane-auth.expires-at:}") String staffExpiresAt,
      @Value("${orderpilot.security.control-plane-auth.revoked:false}") boolean staffRevoked,
      @Value("${orderpilot.security.control-plane-auth.permissions:}") String staffPermissions,
      @Value("${orderpilot.security.control-plane-auth.key-version:}") String staffKeyVersion,
      @Value("${orderpilot.security.control-plane-auth.executor.principal-id:${orderpilot.security.control-plane-auth.executor.credential-alias:}}")
          String executorPrincipalId,
      @Value("${orderpilot.security.control-plane-auth.executor.credential-alias:}") String executorAlias,
      @Value("${orderpilot.security.control-plane-auth.executor.shared-secret:}") String executorSharedSecret,
      @Value("${orderpilot.security.control-plane-auth.executor.audience:}") String executorAudience,
      @Value("${orderpilot.security.control-plane-auth.executor.status:DISABLED}") String executorStatus,
      @Value("${orderpilot.security.control-plane-auth.executor.valid-from:}") String executorValidFrom,
      @Value("${orderpilot.security.control-plane-auth.executor.expires-at:}") String executorExpiresAt,
      @Value("${orderpilot.security.control-plane-auth.executor.revoked:false}") boolean executorRevoked,
      @Value("${orderpilot.security.control-plane-auth.executor.permissions:}") String executorPermissions,
      @Value("${orderpilot.security.control-plane-auth.executor.key-version:}") String executorKeyVersion,
      @Value("${orderpilot.security.gateway-header-auth.shared-secret:}") String gatewaySharedSecret,
      Clock clock) {
    var staff = ControlPlaneCredentialConfigurationValidator.validatedRecordForSlot(
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
        clock,
        false);
    var executor = ControlPlaneCredentialConfigurationValidator.validatedRecordForSlot(
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
        clock,
        false);
    return new ControlPlaneCredentialRegistry(staff, executor, clock);
  }
}
