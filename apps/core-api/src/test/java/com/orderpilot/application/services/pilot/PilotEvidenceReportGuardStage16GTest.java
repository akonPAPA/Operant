package com.orderpilot.application.services.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.analytics.RoiAssumptionsService;
import com.orderpilot.application.services.runtime.FeatureEntitlementGuard;
import com.orderpilot.application.services.runtime.QuotaGuard;
import com.orderpilot.application.services.runtime.RateLimitService;
import com.orderpilot.application.services.runtime.RuntimeFeatureNotAvailableException;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.RuntimeQuotaExceededException;
import com.orderpilot.application.services.runtime.UsageMeterService;
import com.orderpilot.common.tenant.TenantContext;
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
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
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
 * OP-CAP-16G — pilot evidence report generation guarded by entitlement + quota (operator-initiated,
 * no rate consumption) BEFORE the heavier metrics/breakdown aggregation. Proves a disabled
 * REPORT_EXPORT entitlement and an exhausted quota both deny, the allowed path still produces a
 * report, and denials are tenant-scoped.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PilotShadowModeService.class, AuditEventService.class, RoiAssumptionsService.class, CoreConfiguration.class,
  RuntimeGuardService.class, QuotaGuard.class, RateLimitService.class, FeatureEntitlementGuard.class, UsageMeterService.class})
class PilotEvidenceReportGuardStage16GTest {
  private static final RuntimeFeatureType FEATURE = RuntimeFeatureType.REPORT_EXPORT;

  @Autowired private PilotShadowModeService service;
  @Autowired private TenantRuntimePlanRepository planRepository;
  @Autowired private FeatureEntitlementRepository entitlementRepository;
  @Autowired private QuotaPolicyRepository quotaPolicyRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void allowedPathGeneratesReport() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    service.recordShadowRun("INBOUND_DOCUMENT", UUID.randomUUID(), "EXTRACTION", "fixture", "{}", new BigDecimal("0.80"));

    var report = service.evidenceReport();

    assertThat(report).isNotNull();
    assertThat(report.tenantId()).isEqualTo(tenantId);
  }

  @Test
  void disabledEntitlementDeniesBeforeGeneration() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedDisabledEntitlement(tenantId);

    assertThatThrownBy(() -> service.evidenceReport())
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);
  }

  @Test
  void quotaDenialDeniesBeforeGeneration() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // Report units are >= 1; a REPORT_GENERATED quota limit of 0 denies.
    seedReportQuota(tenantId, 0L);

    assertThatThrownBy(() -> service.evidenceReport())
        .isInstanceOf(RuntimeQuotaExceededException.class);
  }

  @Test
  void tenantADenialDoesNotBlockTenantB() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    seedDisabledEntitlement(tenantA);
    assertThatThrownBy(() -> service.evidenceReport())
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);

    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    var report = service.evidenceReport();
    assertThat(report.tenantId()).isEqualTo(tenantB);
  }

  private void seedReportQuota(UUID tenantId, long limit) {
    quotaPolicyRepository.save(
        new QuotaPolicy(
            tenantId, null, UsageMetricType.REPORT_GENERATED, UsagePeriodType.MONTH, limit,
            QuotaEnforcementMode.MONITOR, Instant.now()));
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
        new FeatureEntitlement(tenantId, planId, FEATURE.name(), false, null, from, null, now));
  }
}
