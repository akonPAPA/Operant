package com.orderpilot.application.services.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.runtime.RuntimeFeatureNotAvailableException;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.application.services.runtime.RuntimeQuotaExceededException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.reconciliation.InventoryMovement;
import com.orderpilot.domain.reconciliation.InventoryMovementRepository;
import com.orderpilot.domain.reconciliation.InventoryMovementType;
import com.orderpilot.domain.reconciliation.ReconciliationCaseRepository;
import com.orderpilot.domain.reconciliation.ReconciliationStatus;
import com.orderpilot.domain.usage.FeatureEntitlement;
import com.orderpilot.domain.usage.FeatureEntitlementRepository;
import com.orderpilot.domain.usage.QuotaEnforcementMode;
import com.orderpilot.domain.usage.QuotaPolicy;
import com.orderpilot.domain.usage.QuotaPolicyRepository;
import com.orderpilot.domain.usage.TenantRuntimePlan;
import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanRepository;
import com.orderpilot.domain.usage.TenantRuntimePlanStatus;
import com.orderpilot.domain.usage.UsageMetricType;
import com.orderpilot.domain.usage.UsagePeriodType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-16F — bulk reconciliation guard. {@code InventoryReconciliationService.refreshProjections()}
 * now runs the runtime guard (entitlement → quota → rate) before generating/updating any
 * reconciliation case, with requested units estimated from the already-known product/location pair
 * count. A denial creates no case. Full application context.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReconciliationGuardStage16FTest {
  @Autowired private InventoryReconciliationService service;
  @Autowired private InventoryMovementRepository movementRepository;
  @Autowired private ReconciliationCaseRepository caseRepository;
  @Autowired private InventorySnapshotRepository inventorySnapshotRepository;
  @Autowired private TenantRuntimePlanRepository planRepository;
  @Autowired private FeatureEntitlementRepository entitlementRepository;
  @Autowired private QuotaPolicyRepository quotaPolicyRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // No plan → compatibility default allow → refresh evaluates pairs and creates a mismatch case.
  @Test
  void allowedNoPlanRefreshesAndCreatesCase() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedMismatch(tenantId, UUID.randomUUID(), UUID.randomUUID());

    var response = service.refreshProjections();

    assertThat(response.productLocationPairsEvaluated()).isGreaterThanOrEqualTo(1);
    assertThat(openCases(tenantId)).isEqualTo(1);
  }

  // Disabled RECONCILIATION_RUN entitlement → 403, no case created.
  @Test
  void disabledEntitlementCreatesNoCase() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedMismatch(tenantId, UUID.randomUUID(), UUID.randomUUID());
    seedDisabledEntitlement(tenantId);

    assertThatThrownBy(() -> service.refreshProjections())
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);

    assertThat(openCases(tenantId)).isZero();
  }

  // Quota exhausted → 403, no case created.
  @Test
  void quotaExceededCreatesNoCase() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedMismatch(tenantId, UUID.randomUUID(), UUID.randomUUID());
    seedQuota(tenantId, 0L);

    assertThatThrownBy(() -> service.refreshProjections())
        .isInstanceOf(RuntimeQuotaExceededException.class);

    assertThat(openCases(tenantId)).isZero();
  }

  // Tenant isolation: A disabled denies and creates nothing; B (no plan) refreshes and creates a case.
  @Test
  void tenantIsolation() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();

    TenantContext.setTenantId(tenantA);
    seedMismatch(tenantA, UUID.randomUUID(), UUID.randomUUID());
    seedDisabledEntitlement(tenantA);
    assertThatThrownBy(() -> service.refreshProjections())
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);
    assertThat(openCases(tenantA)).isZero();

    TenantContext.setTenantId(tenantB);
    seedMismatch(tenantB, UUID.randomUUID(), UUID.randomUUID());
    service.refreshProjections();
    assertThat(openCases(tenantB)).isEqualTo(1);
  }

  private long openCases(UUID tenantId) {
    return caseRepository.countByTenantIdAndStatus(tenantId, ReconciliationStatus.OPEN);
  }

  private void seedMismatch(UUID tenantId, UUID productId, UUID locationId) {
    add(tenantId, productId, locationId, InventoryMovementType.OPENING_STOCK, "150", "2026-06-10T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.SALE, "34", "2026-06-11T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.ACTUAL_STOCK_COUNT, "100", "2026-06-12T00:00:00Z");
  }

  private void add(UUID tenantId, UUID productId, UUID locationId, InventoryMovementType type, String qty, String occurredAt) {
    movementRepository.save(
        new InventoryMovement(
            tenantId, productId, locationId, type, new BigDecimal(qty), Instant.parse(occurredAt),
            "TEST", type.name(), Instant.parse(occurredAt)));
  }

  private void seedDisabledEntitlement(UUID tenantId) {
    Instant now = Instant.now();
    Instant from = now.minusSeconds(3600);
    UUID planId =
        planRepository
            .save(new TenantRuntimePlan(
                tenantId, TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, from, null, now))
            .getId();
    entitlementRepository.save(
        new FeatureEntitlement(
            tenantId, planId, RuntimeFeatureType.RECONCILIATION_RUN.name(), false, null, from, null, now));
  }

  private void seedQuota(UUID tenantId, long limit) {
    quotaPolicyRepository.save(
        new QuotaPolicy(
            tenantId, null, UsageMetricType.RECONCILIATION_RUN, UsagePeriodType.MONTH, limit,
            QuotaEnforcementMode.MONITOR, Instant.now()));
  }
}
