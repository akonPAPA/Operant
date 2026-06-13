package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.CreatePlanCommand;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.DisableFeatureCommand;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.UpdatePlanCommand;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.UpsertFeatureCommand;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.usage.FeatureEntitlementRepository;
import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanStatus;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-16I — service-layer tests for {@link RuntimeEntitlementAdminService}: plan create/update,
 * feature upsert/disable, validation, conflict rejection, tenant isolation, audit emission, and proof
 * the persistent runtime policy ({@link RuntimeFeaturePolicy} from {@link CoreConfiguration}) sees
 * command-created records immediately.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RuntimeEntitlementAdminService.class, AuditEventService.class, CoreConfiguration.class})
class RuntimeEntitlementAdminServiceStage16ITest {
  private static final RuntimeFeatureType FEATURE = RuntimeFeatureType.REPORT_EXPORT;
  private static final Instant PAST = Instant.now().minusSeconds(3600);

  @Autowired private RuntimeEntitlementAdminService service;
  @Autowired private RuntimeFeaturePolicy runtimeFeaturePolicy;
  @Autowired private FeatureEntitlementRepository entitlementRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void createPlanPersistsAndAudits() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    var plan = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));

    assertThat(plan.id()).isNotNull();
    assertThat(plan.tenantId()).isEqualTo(tenantId);
    assertThat(plan.planCode()).isEqualTo(TenantRuntimePlanCode.PRO);
    assertThat(auditActions(tenantId)).contains("RUNTIME_PLAN_CREATED");
  }

  @Test
  void createPlanRejectsInvalidEffectiveWindow() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Instant from = Instant.now();

    assertThatThrownBy(() ->
        service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, from, from.minusSeconds(10), null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createPlanRejectsConflictingActivePlan() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));

    assertThatThrownBy(() ->
        service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.ENTERPRISE, TenantRuntimePlanStatus.ACTIVE, PAST, null, null)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void updatePlanToSuspendedDeniesGuardAndAudits() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    var plan = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));
    service.upsertFeatureEntitlement(new UpsertFeatureCommand(plan.id(), FEATURE, true, "granted", PAST, null, null));
    assertThat(runtimeFeaturePolicy.evaluate(tenantId, FEATURE).available()).isTrue();

    service.updatePlan(new UpdatePlanCommand(plan.id(), TenantRuntimePlanStatus.SUSPENDED, null, null, false, null));

    var decision = runtimeFeaturePolicy.evaluate(tenantId, FEATURE);
    assertThat(decision.available()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_PLAN_NOT_ACTIVE);
    assertThat(auditActions(tenantId)).contains("RUNTIME_PLAN_UPDATED");
  }

  @Test
  void upsertEnabledFeatureIsVisibleToPolicyImmediately() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    var plan = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));

    service.upsertFeatureEntitlement(new UpsertFeatureCommand(plan.id(), FEATURE, true, "granted", PAST, null, null));

    var decision = runtimeFeaturePolicy.evaluate(tenantId, FEATURE);
    assertThat(decision.available()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_AVAILABLE);
    assertThat(auditActions(tenantId)).contains("FEATURE_ENTITLEMENT_UPSERTED");
  }

  @Test
  void upsertDisabledFeatureDeniesUnderActivePlan() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    var plan = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));

    service.upsertFeatureEntitlement(new UpsertFeatureCommand(plan.id(), FEATURE, false, "withheld", PAST, null, null));

    var decision = runtimeFeaturePolicy.evaluate(tenantId, FEATURE);
    assertThat(decision.available()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_NOT_AVAILABLE);
  }

  @Test
  void disableExistingFeatureUpdatesSameRowAndAudits() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    var plan = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));
    service.upsertFeatureEntitlement(new UpsertFeatureCommand(plan.id(), FEATURE, true, "granted", PAST, null, null));

    var disabled = service.disableFeatureEntitlement(new DisableFeatureCommand(plan.id(), FEATURE, "revoked", null));

    assertThat(disabled.enabled()).isFalse();
    // No duplicate open-ended row created — the existing one was updated in place.
    assertThat(entitlementRepository.findByTenantIdAndPlanIdAndFeatureType(tenantId, plan.id(), FEATURE.name())).hasSize(1);
    assertThat(runtimeFeaturePolicy.evaluate(tenantId, FEATURE).available()).isFalse();
    assertThat(auditActions(tenantId)).contains("FEATURE_ENTITLEMENT_DISABLED");
  }

  @Test
  void tenantAcannotMutateTenantBPlan() {
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    var planB = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));

    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    assertThatThrownBy(() ->
        service.updatePlan(new UpdatePlanCommand(planB.id(), TenantRuntimePlanStatus.SUSPENDED, null, null, false, null)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() ->
        service.upsertFeatureEntitlement(new UpsertFeatureCommand(planB.id(), FEATURE, false, null, PAST, null, null)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void readReturnsCompatibilityDefaultWhenNoPlan() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    var status = service.getCurrentRuntimeEntitlements();

    assertThat(status.tenantId()).isEqualTo(tenantId);
    assertThat(status.source()).isEqualTo(RuntimeEntitlementAdminService.SOURCE_COMPATIBILITY_DEFAULT);
    assertThat(status.currentPlan()).isNull();
    assertThat(status.featureStatuses())
        .allSatisfy(fs -> {
          assertThat(fs.available()).isTrue();
          assertThat(fs.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_POLICY_COMPAT_DEFAULT);
        });
  }

  private java.util.List<String> auditActions(UUID tenantId) {
    return auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream()
        .map(com.orderpilot.domain.audit.AuditEvent::getAction)
        .toList();
  }
}
