package com.orderpilot.application.services.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
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
 * OP-CAP-16H — Stage 8 pilot ROI report export ({@link BusinessValueAnalyticsService#export()})
 * guarded by entitlement + quota (operator-initiated, no rate consumption) BEFORE the heavier
 * multi-source metrics aggregation. Proves a disabled REPORT_EXPORT entitlement and an exhausted
 * quota both deny before report generation, the allowed path still produces a report (with or
 * without report-driving data), and denials are tenant-scoped. Reuses the 16G REPORT_EXPORT /
 * REPORT_GENERATED feature/operation; no new runtime API.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BusinessValueAnalyticsService.class, RoiAssumptionsService.class, CommerceAnalyticsService.class, CoreConfiguration.class,
  RuntimeGuardService.class, QuotaGuard.class, RateLimitService.class, FeatureEntitlementGuard.class, UsageMeterService.class})
class Stage8ValueAnalyticsExportGuardStage16HTest {
  private static final RuntimeFeatureType FEATURE = RuntimeFeatureType.REPORT_EXPORT;
  private static final Instant NOW = Instant.parse("2026-05-27T00:00:00Z");

  @Autowired private BusinessValueAnalyticsService service;
  @Autowired private ExceptionCaseRepository exceptionCaseRepository;
  @Autowired private TenantRuntimePlanRepository planRepository;
  @Autowired private FeatureEntitlementRepository entitlementRepository;
  @Autowired private QuotaPolicyRepository quotaPolicyRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void allowedExportStillWorksWithNoData() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    var report = service.export();

    assertThat(report).isNotNull();
    assertThat(report.tenantId()).isEqualTo(tenantId);
  }

  @Test
  void allowedExportStillWorksWithReportDrivingData() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedExceptionCase(tenantId);

    var report = service.export();

    assertThat(report).isNotNull();
    assertThat(report.tenantId()).isEqualTo(tenantId);
  }

  @Test
  void disabledEntitlementDeniesBeforeGeneration() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedExceptionCase(tenantId);
    seedDisabledEntitlement(tenantId);

    assertThatThrownBy(() -> service.export())
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);
  }

  @Test
  void quotaDenialDeniesBeforeGeneration() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedExceptionCase(tenantId);
    // Report units are >= 1; a REPORT_GENERATED quota limit of 0 denies before generation.
    seedReportQuota(tenantId, 0L);

    assertThatThrownBy(() -> service.export())
        .isInstanceOf(RuntimeQuotaExceededException.class);
  }

  @Test
  void cheapExceptionCountUnitsStayWithinSmallQuota() {
    // The cheap tenant-scoped COUNT(exception_case) feeds forReport → ceil(count / 1000); a handful
    // of cases still resolves to 1 unit, so a quota limit of 1 must allow the export.
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedExceptionCase(tenantId);
    seedExceptionCase(tenantId);
    seedReportQuota(tenantId, 1L);

    var report = service.export();

    assertThat(report.tenantId()).isEqualTo(tenantId);
  }

  @Test
  void tenantADenialDoesNotBlockTenantB() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    seedDisabledEntitlement(tenantA);
    assertThatThrownBy(() -> service.export())
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);

    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    var report = service.export();
    assertThat(report.tenantId()).isEqualTo(tenantB);
  }

  private void seedExceptionCase(UUID tenantId) {
    UUID runId = UUID.randomUUID();
    exceptionCaseRepository.save(new ExceptionCase(tenantId, "VAL-" + UUID.randomUUID(), "VALIDATION_RUN", runId, UUID.randomUUID(), runId, null,
        "Validation review", "OPEN", "HIGH", "ERROR", "review", NOW));
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
