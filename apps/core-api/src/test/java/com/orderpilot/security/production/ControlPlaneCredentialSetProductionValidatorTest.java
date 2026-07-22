package com.orderpilot.security.production;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Production-like startup proofs for stable logical control-principal identity. */
class ControlPlaneCredentialSetProductionValidatorTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
  private static final String AUDIENCE = "orderpilot-control-plane";
  private static final String VALID_FROM = "2026-01-01T00:00:00Z";
  private static final String EXPIRES_AT = "2035-01-01T00:00:00Z";
  private static final String GATEWAY_SECRET =
      "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517";
  private static final String STAFF_SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  private static final String EXECUTOR_SECRET =
      "11112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

  @Test
  void enabledStaffCredentialRequiresExplicitStablePrincipalId() {
    ControlPlaneCredentialSetProductionValidator validator = validator(
        "", "ops-staff", STAFF_SECRET, "ENABLED",
        "", "", "", "DISABLED");

    assertThatThrownBy(validator::afterPropertiesSet)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("control-plane-auth.principal-id");
  }

  @Test
  void enabledExecutorCredentialRequiresExplicitStablePrincipalId() {
    ControlPlaneCredentialSetProductionValidator validator = validator(
        "", "", "", "DISABLED",
        "", "ops-executor", EXECUTOR_SECRET, "ENABLED");

    assertThatThrownBy(validator::afterPropertiesSet)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("control-plane-auth.executor.principal-id");
  }

  private static ControlPlaneCredentialSetProductionValidator validator(
      String staffPrincipalId,
      String staffAlias,
      String staffSecret,
      String staffStatus,
      String executorPrincipalId,
      String executorAlias,
      String executorSecret,
      String executorStatus) {
    MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "production");
    environment.setActiveProfiles("production");
    return new ControlPlaneCredentialSetProductionValidator(
        environment,
        CLOCK,
        GATEWAY_SECRET,
        staffPrincipalId,
        staffAlias,
        staffSecret,
        AUDIENCE,
        staffStatus,
        VALID_FROM,
        EXPIRES_AT,
        false,
        "STAFF_CONTROL_BACKUP,STAFF_CONTROL_LIFECYCLE_READ",
        "staff-v1",
        executorPrincipalId,
        executorAlias,
        executorSecret,
        AUDIENCE,
        executorStatus,
        VALID_FROM,
        EXPIRES_AT,
        false,
        "CONTROL_EXECUTOR_LEASE,CONTROL_EXECUTOR_REPORT",
        "executor-v1");
  }
}
