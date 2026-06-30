package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.Stage2Dtos.ImportJobRequest;
import com.orderpilot.application.services.runtime.RuntimeFeatureNotAvailableException;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.application.services.runtime.RuntimeQuotaExceededException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.imports.ImportJob;
import com.orderpilot.domain.product.ProductRepository;
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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-16F — bulk import activation guard. {@code ImportJobService.activate(...)} now runs the
 * runtime guard (entitlement → quota → rate) before applying any staged row to a business table,
 * with requested units estimated from the stored row count. A denial applies nothing and leaves the
 * job VALIDATED. Full application context so the persistent policy / guard chain / estimator wire.
 */
@SpringBootTest
@ActiveProfiles("test")
class BulkImportGuardStage16FTest {
  @Autowired private ImportJobService service;
  @Autowired private ProductRepository productRepository;
  @Autowired private TenantRuntimePlanRepository planRepository;
  @Autowired private FeatureEntitlementRepository entitlementRepository;
  @Autowired private QuotaPolicyRepository quotaPolicyRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // No plan → compatibility default allow → activate applies the product.
  @Test
  void allowedNoPlanAppliesProducts() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID jobId = validatedJob(tenantId, productsCsv(1));

    ImportJob activated = service.activate(jobId);

    assertThat(activated.getStatus()).isEqualTo("APPLIED");
    assertThat(productRepository.findByTenantIdAndDeletedAtIsNullOrderBySku(tenantId)).hasSize(1);
  }

  // Disabled BULK_IMPORT entitlement → 403, nothing applied, job stays VALIDATED.
  @Test
  void disabledEntitlementAppliesNothing() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID jobId = validatedJob(tenantId, productsCsv(1));
    seedDisabledEntitlement(tenantId);

    assertThatThrownBy(() -> service.activate(jobId))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);

    assertThat(productRepository.findByTenantIdAndDeletedAtIsNullOrderBySku(tenantId)).isEmpty();
    assertThat(service.get(jobId).getStatus()).isEqualTo("VALIDATED");
  }

  // Quota exhausted → 403, nothing applied.
  @Test
  void quotaExceededAppliesNothing() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID jobId = validatedJob(tenantId, productsCsv(1));
    seedQuota(tenantId, 0L);

    assertThatThrownBy(() -> service.activate(jobId))
        .isInstanceOf(RuntimeQuotaExceededException.class);

    assertThat(productRepository.findByTenantIdAndDeletedAtIsNullOrderBySku(tenantId)).isEmpty();
  }

  // Requested units use the stored row count: 150 rows → ceil(150/100)=2 > limit 1 → quota denial.
  @Test
  void requestedUnitsScaleWithRowCount() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID jobId = validatedJob(tenantId, productsCsv(150));
    seedQuota(tenantId, 1L); // a hardcoded 1 unit would pass; estimated 2 units must fail

    assertThatThrownBy(() -> service.activate(jobId))
        .isInstanceOf(RuntimeQuotaExceededException.class);

    assertThat(productRepository.findByTenantIdAndDeletedAtIsNullOrderBySku(tenantId)).isEmpty();
  }

  // Tenant isolation: A disabled denies; B (no plan) applies.
  @Test
  void tenantIsolation() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();

    TenantContext.setTenantId(tenantA);
    UUID jobA = validatedJob(tenantA, productsCsv(1));
    seedDisabledEntitlement(tenantA);
    assertThatThrownBy(() -> service.activate(jobA))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);
    assertThat(productRepository.findByTenantIdAndDeletedAtIsNullOrderBySku(tenantA)).isEmpty();

    TenantContext.setTenantId(tenantB);
    UUID jobB = validatedJob(tenantB, productsCsv(1));
    assertThat(service.activate(jobB).getStatus()).isEqualTo("APPLIED");
    assertThat(productRepository.findByTenantIdAndDeletedAtIsNullOrderBySku(tenantB)).hasSize(1);
  }

  private UUID validatedJob(UUID tenantId, String csv) {
    ImportJob job = service.create(new ImportJobRequest(null, "PRODUCTS", "bulk.csv", csv), null);
    var report = service.validate(job.getId());
    assertThat(report.validationErrors()).isEmpty();
    return job.getId();
  }

  private String productsCsv(int rows) {
    StringBuilder sb = new StringBuilder("sku,name,baseUom\n");
    for (int i = 0; i < rows; i++) {
      sb.append("BULK-").append(i).append(",Pad ").append(i).append(",EA\n");
    }
    return sb.toString();
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
            tenantId, planId, RuntimeFeatureType.BULK_IMPORT.name(), false, null, from, null, now));
  }

  private void seedQuota(UUID tenantId, long limit) {
    quotaPolicyRepository.save(
        new QuotaPolicy(
            tenantId, null, UsageMetricType.AI_INPUT_UNITS, UsagePeriodType.MONTH, limit,
            QuotaEnforcementMode.MONITOR, Instant.now()));
  }
}
