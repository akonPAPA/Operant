package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Direct architecture proofs for the bounded staff + executor credential registry. */
class ControlPlaneMultiCredentialRegistryTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
  private static final String AUDIENCE = ControlPlaneProtocol.AUDIENCE;
  private static final String VALID_FROM = "2026-01-01T00:00:00Z";
  private static final String EXPIRES_AT = "2035-01-01T00:00:00Z";
  private static final String STAFF_SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  private static final String EXECUTOR_SECRET =
      "11112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

  @Test
  void staffAndExecutorAreActiveTogetherWithTypedDisjointPrincipals() {
    ControlPlaneCredentialRegistry registry = registry(
        staff("staff:operations", "ops-staff", STAFF_SECRET, "staff-v1"),
        executor("executor:lifecycle", "ops-executor", EXECUTOR_SECRET, "executor-v1"));

    ControlPlanePrincipal staff = registry.findActive("ops-staff", AUDIENCE).orElseThrow().principal();
    ControlPlanePrincipal executor = registry.findActive("ops-executor", AUDIENCE).orElseThrow().principal();

    assertThat(staff.principalType()).isEqualTo(ControlPlanePrincipalType.OPERANT_STAFF);
    assertThat(executor.principalType()).isEqualTo(ControlPlanePrincipalType.LIFECYCLE_EXECUTOR);
    assertThat(staff.principalId()).isEqualTo("staff:operations");
    assertThat(executor.principalId()).isEqualTo("executor:lifecycle");
    assertThat(staff).isNotEqualTo(executor);
  }

  @Test
  void credentialRotationDoesNotChangeLogicalPrincipalFingerprint() {
    ControlPlanePrincipal before = new ControlPlanePrincipal(
        "executor:lifecycle",
        "ops-executor-v1",
        "key-v1",
        ControlPlanePrincipalType.LIFECYCLE_EXECUTOR);
    ControlPlanePrincipal after = new ControlPlanePrincipal(
        "executor:lifecycle",
        "ops-executor-v2",
        "key-v2",
        ControlPlanePrincipalType.LIFECYCLE_EXECUTOR);

    assertThat(ControlPlanePrincipalFingerprint.of(after))
        .isEqualTo(ControlPlanePrincipalFingerprint.of(before))
        .hasSize(64);
  }

  @Test
  void duplicateAliasPrincipalIdAndKeyMaterialAreRejected() {
    var staff = staff("staff:operations", "shared-alias", STAFF_SECRET, "staff-v1");

    assertThatThrownBy(() -> registry(
        staff,
        executor("executor:lifecycle", "shared-alias", EXECUTOR_SECRET, "executor-v1")))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> registry(
        staff,
        executor("staff:operations", "ops-executor", EXECUTOR_SECRET, "executor-v1")))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> registry(
        staff,
        executor("executor:lifecycle", "ops-executor", STAFF_SECRET, "executor-v1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fixedPrincipalSlotsRejectTheOtherPermissionFamilyAndTenantPermissions() {
    assertThatThrownBy(() -> record(
        "orderpilot.security.control-plane-auth",
        "staff:operations",
        ControlPlanePrincipalType.OPERANT_STAFF,
        "ops-staff",
        STAFF_SECRET,
        "CONTROL_EXECUTOR_LEASE",
        "staff-v1"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> record(
        "orderpilot.security.control-plane-auth.executor",
        "executor:lifecycle",
        ControlPlanePrincipalType.LIFECYCLE_EXECUTOR,
        "ops-executor",
        EXECUTOR_SECRET,
        "STAFF_CONTROL_BACKUP",
        "executor-v1"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> record(
        "orderpilot.security.control-plane-auth",
        "staff:operations",
        ControlPlanePrincipalType.OPERANT_STAFF,
        "ops-staff",
        STAFF_SECRET,
        "QUOTE_ACTION",
        "staff-v1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ControlPlaneCredentialRegistry registry(
      Optional<ControlPlaneCredentialRegistry.ControlPlaneCredentialRecord> staff,
      Optional<ControlPlaneCredentialRegistry.ControlPlaneCredentialRecord> executor) {
    return new ControlPlaneCredentialRegistry(staff, executor, CLOCK);
  }

  private static Optional<ControlPlaneCredentialRegistry.ControlPlaneCredentialRecord> staff(
      String principalId,
      String alias,
      String secret,
      String keyVersion) {
    return record(
        "orderpilot.security.control-plane-auth",
        principalId,
        ControlPlanePrincipalType.OPERANT_STAFF,
        alias,
        secret,
        "STAFF_CONTROL_BACKUP,STAFF_CONTROL_LIFECYCLE_READ",
        keyVersion);
  }

  private static Optional<ControlPlaneCredentialRegistry.ControlPlaneCredentialRecord> executor(
      String principalId,
      String alias,
      String secret,
      String keyVersion) {
    return record(
        "orderpilot.security.control-plane-auth.executor",
        principalId,
        ControlPlanePrincipalType.LIFECYCLE_EXECUTOR,
        alias,
        secret,
        "CONTROL_EXECUTOR_LEASE,CONTROL_EXECUTOR_REPORT",
        keyVersion);
  }

  private static Optional<ControlPlaneCredentialRegistry.ControlPlaneCredentialRecord> record(
      String prefix,
      String principalId,
      ControlPlanePrincipalType principalType,
      String alias,
      String secret,
      String permissions,
      String keyVersion) {
    return ControlPlaneCredentialConfigurationValidator.validatedRecordForSlot(
        prefix,
        principalId,
        principalType,
        alias,
        secret,
        AUDIENCE,
        "ENABLED",
        VALID_FROM,
        EXPIRES_AT,
        false,
        permissions,
        keyVersion,
        null,
        CLOCK,
        true);
  }
}
