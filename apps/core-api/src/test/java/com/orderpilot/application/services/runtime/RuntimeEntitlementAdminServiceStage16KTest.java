package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.CreatePlanCommand;
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
 * OP-CAP-16K — service-level bounded-window overlap rules for ACTIVE runtime plans: overlapping
 * windows conflict (409), adjacent windows are allowed, open-ended overlaps any later ACTIVE window,
 * non-ACTIVE plans never conflict, and conflicts are tenant-scoped.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RuntimeEntitlementAdminService.class, AuditEventService.class, CoreConfiguration.class})
class RuntimeEntitlementAdminServiceStage16KTest {
  private static final Instant JAN1 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant JAN15 = Instant.parse("2026-01-15T00:00:00Z");
  private static final Instant JAN31 = Instant.parse("2026-01-31T00:00:00Z");
  private static final Instant FEB1 = Instant.parse("2026-02-01T00:00:00Z");
  private static final Instant FEB15 = Instant.parse("2026-02-15T00:00:00Z");
  private static final Instant FEB28 = Instant.parse("2026-02-28T00:00:00Z");
  private static final Instant MAR1 = Instant.parse("2026-03-01T00:00:00Z");

  @Autowired private RuntimeEntitlementAdminService service;
  @Autowired private TenantRuntimePlanRepository planRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void overlappingActiveWindowsConflict() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, JAN1, JAN31, null));

    assertThatThrownBy(() ->
        service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.ENTERPRISE, TenantRuntimePlanStatus.ACTIVE, JAN15, FEB15, null)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void adjacentNonOverlappingActiveWindowsAllowed() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, JAN1, JAN31, null));

    var second = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.ENTERPRISE, TenantRuntimePlanStatus.ACTIVE, FEB1, FEB28, null));

    assertThat(second.id()).isNotNull();
  }

  @Test
  void openEndedActiveConflictsWithLaterBoundedActive() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, JAN1, null, null));

    assertThatThrownBy(() ->
        service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.ENTERPRISE, TenantRuntimePlanStatus.ACTIVE, FEB1, FEB28, null)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void newOpenEndedActiveConflictsWithExistingFutureBoundedActive() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // Seed a future bounded ACTIVE plan directly, then a new open-ended ACTIVE plan whose window
    // spans it must conflict.
    planRepository.save(new TenantRuntimePlan(tenantId, TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, FEB1, FEB28, Instant.now()));

    assertThatThrownBy(() ->
        service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.ENTERPRISE, TenantRuntimePlanStatus.ACTIVE, JAN1, null, null)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void suspendedOverlappingPlanDoesNotConflict() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    planRepository.save(new TenantRuntimePlan(tenantId, TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.SUSPENDED, JAN1, MAR1, Instant.now()));

    var created = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.ENTERPRISE, TenantRuntimePlanStatus.ACTIVE, JAN15, FEB15, null));

    assertThat(created.id()).isNotNull();
  }

  @Test
  void overlappingActiveWindowsDoNotConflictAcrossTenants() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, JAN1, JAN31, null));

    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    var created = service.createPlan(new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, JAN15, FEB15, null));

    assertThat(created.id()).isNotNull();
  }
}
