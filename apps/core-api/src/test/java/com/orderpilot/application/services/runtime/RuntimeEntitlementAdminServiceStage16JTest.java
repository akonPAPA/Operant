package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.CreatePlanCommand;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.UpdatePlanCommand;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.usage.TenantRuntimePlan;
import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanRepository;
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
 * OP-CAP-16J — service-layer hardening tests: explicit {@code effectiveUntil} patch semantics
 * (set / clear / leave-unchanged), invalid-window rejection, and the at-most-one open-ended ACTIVE
 * plan invariant (service-level; the V43 DB partial index enforces the same on Postgres). Historical
 * closed-active and suspended plans must not conflict.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RuntimeEntitlementAdminService.class, AuditEventService.class, CoreConfiguration.class})
class RuntimeEntitlementAdminServiceStage16JTest {
  private static final Instant PAST = Instant.now().minusSeconds(3600);
  private static final Instant FUTURE = Instant.now().plusSeconds(3600);
  private static final Instant FAR_FUTURE = Instant.now().plusSeconds(7200);

  @Autowired private RuntimeEntitlementAdminService service;
  @Autowired private TenantRuntimePlanRepository planRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void clearingEffectiveUntilSetsPlanOpenEnded() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    var plan = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, FUTURE, null));
    assertThat(plan.effectiveUntil()).isEqualTo(FUTURE);

    var updated = service.updatePlan(new UpdatePlanCommand(plan.id(), null, null, null, true, null));

    assertThat(updated.effectiveUntil()).isNull();
  }

  @Test
  void omittedEffectiveUntilLeavesValueUnchanged() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    var plan = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, FUTURE, null));

    var updated = service.updatePlan(new UpdatePlanCommand(plan.id(), TenantRuntimePlanStatus.SUSPENDED, null, null, false, null));

    assertThat(updated.status()).isEqualTo(TenantRuntimePlanStatus.SUSPENDED);
    assertThat(updated.effectiveUntil()).isEqualTo(FUTURE);
  }

  @Test
  void settingEffectiveUntilWorks() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    var plan = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));

    var updated = service.updatePlan(new UpdatePlanCommand(plan.id(), null, null, FUTURE, false, null));

    assertThat(updated.effectiveUntil()).isEqualTo(FUTURE);
  }

  @Test
  void invalidWindowIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    var plan = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));

    assertThatThrownBy(() ->
        service.updatePlan(new UpdatePlanCommand(plan.id(), null, FAR_FUTURE, FUTURE, false, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void clearingAndSettingEffectiveUntilTogetherIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    var plan = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));

    assertThatThrownBy(() ->
        service.updatePlan(new UpdatePlanCommand(plan.id(), null, null, FUTURE, true, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void clearingEffectiveUntilConflictsWhenAnotherOpenActivePlanExists() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // Seed directly to construct a state two service createPlan calls would refuse: one open-ended
    // active plan plus one bounded active plan.
    Instant now = Instant.now();
    planRepository.save(new TenantRuntimePlan(tenantId, TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, now));
    UUID boundedId =
        planRepository.save(new TenantRuntimePlan(tenantId, TenantRuntimePlanCode.ENTERPRISE, TenantRuntimePlanStatus.ACTIVE, PAST, FUTURE, now)).getId();

    assertThatThrownBy(() ->
        service.updatePlan(new UpdatePlanCommand(boundedId, null, null, null, true, null)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void serviceConflictPreventsSecondActivePlan() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));

    assertThatThrownBy(() ->
        service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.ENTERPRISE, TenantRuntimePlanStatus.ACTIVE, PAST, null, null)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void historicalClosedActivePlanDoesNotConflict() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // A previously-active plan whose window has closed (effective_until in the past) is not active now.
    planRepository.save(new TenantRuntimePlan(tenantId, TenantRuntimePlanCode.PILOT, TenantRuntimePlanStatus.ACTIVE,
        PAST.minusSeconds(7200), PAST, Instant.now()));

    var created = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));

    assertThat(created.id()).isNotNull();
    assertThat(created.effectiveUntil()).isNull();
  }

  @Test
  void suspendedPlanDoesNotConflict() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    planRepository.save(new TenantRuntimePlan(tenantId, TenantRuntimePlanCode.PILOT, TenantRuntimePlanStatus.SUSPENDED, PAST, null, Instant.now()));

    var created = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));

    assertThat(created.id()).isNotNull();
  }
}
