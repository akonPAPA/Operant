package com.orderpilot.application.services.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.analytics.RoiAssumptionsService;
import com.orderpilot.application.services.runtime.FeatureEntitlementGuard;
import com.orderpilot.application.services.runtime.QuotaGuard;
import com.orderpilot.application.services.runtime.RateLimitService;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.UsageMeterService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.common.tenant.TenantContextMissingException;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.pilot.HumanCorrection;
import com.orderpilot.domain.pilot.HumanCorrectionRepository;
import com.orderpilot.domain.pilot.ShadowRun;
import com.orderpilot.domain.pilot.ShadowRunRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PilotShadowModeService.class, AuditEventService.class, RoiAssumptionsService.class, CoreConfiguration.class,
  RuntimeGuardService.class, QuotaGuard.class, RateLimitService.class, FeatureEntitlementGuard.class, UsageMeterService.class})
class PilotShadowModeServiceTest {
  @Autowired private PilotShadowModeService service;
  @Autowired private ShadowRunRepository shadowRunRepository;
  @Autowired private HumanCorrectionRepository humanCorrectionRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void creatingShadowRunRecordsTenantScopedMockOnlyAdvisoryOutput() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    ShadowRun run = service.recordShadowRun("INBOUND_DOCUMENT", UUID.randomUUID(), "EXTRACTION", "stage10b-mock-fixture", "{\"field\":\"sku\"}", new BigDecimal("0.8000"));

    assertThat(run.getTenantId()).isEqualTo(tenantId);
    assertThat(run.getProviderMode()).isEqualTo("MOCK_ONLY");
    assertThat(run.getStatus()).isEqualTo("RECORDED");
    assertThat(shadowRunRepository.findByIdAndTenantId(run.getId(), tenantId)).isPresent();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("PILOT_SHADOW_RUN_RECORDED");
  }

  @Test
  void creatingShadowRunRequiresTenantContext() {
    assertThatThrownBy(() -> service.recordShadowRun("INBOUND_DOCUMENT", UUID.randomUUID(), "EXTRACTION", "stage10b-mock-fixture", "{}", new BigDecimal("0.9000")))
        .isInstanceOf(TenantContextMissingException.class);
    assertThat(shadowRunRepository.count()).isZero();
  }

  @Test
  void acceptedCorrectionLinksToShadowRunAndMarksItAccepted() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ShadowRun run = service.recordShadowRun("DRAFT_QUOTE", UUID.randomUUID(), "QUOTE_DRAFT", "stage10b-mock-fixture", "{\"total\":100}", new BigDecimal("0.7000"));

    HumanCorrection correction = service.recordCorrection(run.getId(), null, "ACCEPTED", "{\"total\":100}", "{\"total\":100}", "Looks correct");

    assertThat(correction.getShadowRunId()).isEqualTo(run.getId());
    assertThat(correction.getCorrectionType()).isEqualTo("ACCEPTED");
    ShadowRun reviewed = shadowRunRepository.findByIdAndTenantId(run.getId(), tenantId).orElseThrow();
    assertThat(reviewed.getStatus()).isEqualTo("ACCEPTED");
    assertThat(reviewed.getReviewedAt()).isNotNull();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("PILOT_HUMAN_CORRECTION_RECORDED");
  }

  @Test
  void fieldCorrectedStoresBeforeAndAfterPayloads() {
    TenantContext.setTenantId(UUID.randomUUID());
    ShadowRun run = service.recordShadowRun("CHANNEL_MESSAGE", UUID.randomUUID(), "EXTRACTION", "stage10b-mock-fixture", "{\"sku\":\"ABC\"}", new BigDecimal("0.6000"));

    HumanCorrection correction = service.recordCorrection(run.getId(), UUID.randomUUID(), "FIELD_CORRECTED", "{\"sku\":\"ABC\"}", "{\"sku\":\"ABC-1\"}", "Human corrected SKU suffix");

    assertThat(correction.getBeforePayloadJson()).contains("ABC");
    assertThat(correction.getAfterPayloadJson()).contains("ABC-1");
    assertThat(correction.getCorrectionReason()).contains("SKU");
    assertThat(humanCorrectionRepository.findByTenantIdAndShadowRunIdOrderByCreatedAtDesc(run.getTenantId(), run.getId())).hasSize(1);
    assertThat(shadowRunRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("CORRECTED");
  }

  @Test
  void metricsAggregateShadowRunReviewAndCorrectionCounts() {
    TenantContext.setTenantId(UUID.randomUUID());
    ShadowRun accepted = service.recordShadowRun("INBOUND_DOCUMENT", UUID.randomUUID(), "EXTRACTION", "stage10b-mock-fixture", "{}", new BigDecimal("0.9000"));
    ShadowRun corrected = service.recordShadowRun("VALIDATION_CASE", UUID.randomUUID(), "VALIDATION", "stage10b-mock-fixture", "{}", new BigDecimal("0.5000"));
    ShadowRun rejected = service.recordShadowRun("DRAFT_ORDER", UUID.randomUUID(), "ORDER_DRAFT", "stage10b-mock-fixture", "{}", new BigDecimal("0.7000"));
    service.recordCorrection(accepted.getId(), null, "ACCEPTED", "{}", "{}", null);
    service.recordCorrection(corrected.getId(), null, "FIELD_CORRECTED", "{\"qty\":1}", "{\"qty\":2}", "Quantity mismatch");
    service.recordCorrection(rejected.getId(), null, "SUBSTITUTION_REJECTED", "{}", "{}", "Wrong substitute");

    PilotShadowModeService.PilotMetrics metrics = service.metrics();

    assertThat(metrics.totalShadowRuns()).isEqualTo(3);
    assertThat(metrics.reviewedShadowRuns()).isEqualTo(3);
    assertThat(metrics.acceptedCount()).isEqualTo(1);
    assertThat(metrics.correctedCount()).isEqualTo(1);
    assertThat(metrics.rejectedCount()).isEqualTo(1);
    assertThat(metrics.humanCorrectionRate()).isEqualByComparingTo("0.3333");
    assertThat(metrics.averageConfidence()).isEqualByComparingTo("0.7000");
    assertThat(metrics.predictionTypeBreakdown()).containsEntry("EXTRACTION", 1L).containsEntry("VALIDATION", 1L).containsEntry("ORDER_DRAFT", 1L);
    assertThat(metrics.correctionTypeBreakdown()).containsEntry("ACCEPTED", 1L).containsEntry("FIELD_CORRECTED", 1L).containsEntry("SUBSTITUTION_REJECTED", 1L);
  }

  // --- OP-CAP-11F pilot ROI readiness ---

  @Test
  void metricsComputeDeterministicRoiCycleTimeAndCandidateCounts() {
    TenantContext.setTenantId(UUID.randomUUID());
    // baseline 12 / assisted 3 -> saved 9; automation candidate.
    service.recordShadowRun("INBOUND_DOCUMENT", UUID.randomUUID(), "EXTRACTION", "stage10b-mock-fixture", "{}", new BigDecimal("0.9000"),
        null, new BigDecimal("12.00"), new BigDecimal("3.00"), true, false);
    // baseline 8 / assisted 5 -> saved 3; review required.
    service.recordShadowRun("CHANNEL_MESSAGE", UUID.randomUUID(), "EXTRACTION", "stage10b-mock-fixture", "{}", new BigDecimal("0.5000"),
        "MARGIN_VIOLATION", new BigDecimal("8.00"), new BigDecimal("5.00"), false, true);

    PilotShadowModeService.PilotMetrics metrics = service.metrics();

    assertThat(metrics.estimatedMinutesSaved()).isEqualByComparingTo("12.00");
    assertThat(metrics.averageManualBaselineMinutes()).isEqualByComparingTo("10.00");
    assertThat(metrics.averageAssistedMinutes()).isEqualByComparingTo("4.00");
    assertThat(metrics.automationCandidateCount()).isEqualTo(1);
    assertThat(metrics.reviewRequiredCount()).isEqualTo(1);
    // Default tenant ROI assumptions: $45.00/hr fully-loaded operator cost. 12 min -> 0.2 hr -> $9.00.
    assertThat(metrics.estimatedCostSaved()).isEqualByComparingTo("9.00");
    assertThat(metrics.costCurrency()).isEqualTo("USD");
  }

  @Test
  void exceptionBreakdownGroupsCategoriesWithDeterministicPercentages() {
    TenantContext.setTenantId(UUID.randomUUID());
    service.recordShadowRun("DRAFT_QUOTE", UUID.randomUUID(), "SUBSTITUTION", "fix", "{}", null, "OUT_OF_STOCK_SUBSTITUTE", null, null, false, true);
    service.recordShadowRun("DRAFT_QUOTE", UUID.randomUUID(), "SUBSTITUTION", "fix", "{}", null, "OUT_OF_STOCK_SUBSTITUTE", null, null, false, true);
    service.recordShadowRun("DRAFT_QUOTE", UUID.randomUUID(), "VALIDATION", "fix", "{}", null, "MARGIN_VIOLATION", null, null, false, true);
    // Uncategorized run must not appear in the exception breakdown.
    service.recordShadowRun("DRAFT_QUOTE", UUID.randomUUID(), "EXTRACTION", "fix", "{}", null, null, null, null, false, false);

    assertThat(service.metrics().exceptionCategoryCounts())
        .containsEntry("OUT_OF_STOCK_SUBSTITUTE", 2L)
        .containsEntry("MARGIN_VIOLATION", 1L)
        .doesNotContainKey(null);

    List<PilotShadowModeService.ExceptionCategorySlice> breakdown = service.exceptionBreakdown();
    assertThat(breakdown).hasSize(2);
    assertThat(breakdown).anySatisfy(slice -> {
      assertThat(slice.category()).isEqualTo("OUT_OF_STOCK_SUBSTITUTE");
      assertThat(slice.count()).isEqualTo(2);
      assertThat(slice.percentage()).isEqualByComparingTo("0.6667");
    });
  }

  // --- OP-CAP-11G pilot evidence report pack ---

  @Test
  void evidenceReportComposesDeterministicRoiCycleTimeAndTopExceptions() {
    TenantContext.setTenantId(UUID.randomUUID());
    // baseline 12 / assisted 3 -> saved 9; automation candidate.
    service.recordShadowRun("INBOUND_DOCUMENT", UUID.randomUUID(), "EXTRACTION", "fix", "{}", new BigDecimal("0.9000"),
        null, new BigDecimal("12.00"), new BigDecimal("3.00"), true, false);
    // baseline 8 / assisted 5 -> saved 3; margin violation; corrected below.
    ShadowRun corrected = service.recordShadowRun("CHANNEL_MESSAGE", UUID.randomUUID(), "EXTRACTION", "fix", "{}", new BigDecimal("0.5000"),
        "MARGIN_VIOLATION", new BigDecimal("8.00"), new BigDecimal("5.00"), false, true);
    service.recordShadowRun("DRAFT_QUOTE", UUID.randomUUID(), "VALIDATION", "fix", "{}", null, "MARGIN_VIOLATION", null, null, false, true);
    service.recordShadowRun("DRAFT_QUOTE", UUID.randomUUID(), "SUBSTITUTION", "fix", "{}", null, "OUT_OF_STOCK_SUBSTITUTE", null, null, false, true);
    service.recordCorrection(corrected.getId(), null, "FIELD_CORRECTED", "{}", "{}", "Adjusted margin");

    PilotShadowModeService.EvidenceReport report = service.evidenceReport();

    assertThat(report.reportGeneratedAt()).isNotNull();
    assertThat(report.metrics().totalShadowRuns()).isEqualTo(4);
    assertThat(report.totalHumanCorrections()).isEqualTo(1);
    assertThat(report.metrics().estimatedMinutesSaved()).isEqualByComparingTo("12.00");
    assertThat(report.metrics().estimatedCostSaved()).isEqualByComparingTo("9.00");
    assertThat(report.metrics().costCurrency()).isEqualTo("USD");
    assertThat(report.metrics().automationCandidateCount()).isEqualTo(1);
    // Top exception category is the most frequent one (MARGIN_VIOLATION = 2), deterministically first.
    assertThat(report.topExceptionCategories()).isNotEmpty();
    assertThat(report.topExceptionCategories().getFirst().category()).isEqualTo("MARGIN_VIOLATION");
    assertThat(report.topExceptionCategories().getFirst().count()).isEqualTo(2);
    // Readiness signals are present and deterministic.
    assertThat(report.readinessSignals()).anySatisfy(signal -> {
      assertThat(signal.label()).isEqualTo("Sample size");
      assertThat(signal.assessment()).isEqualTo("LIMITED_SAMPLE");
    });
    assertThat(report.limitations()).isNotEmpty();
    assertThat(report.safetyStatement()).contains("advisory");
  }

  @Test
  void evidenceReportHandlesEmptyTenantData() {
    TenantContext.setTenantId(UUID.randomUUID());

    PilotShadowModeService.EvidenceReport report = service.evidenceReport();

    assertThat(report.metrics().totalShadowRuns()).isZero();
    assertThat(report.totalHumanCorrections()).isZero();
    assertThat(report.metrics().estimatedMinutesSaved()).isEqualByComparingTo("0.00");
    assertThat(report.exceptionBreakdown()).isEmpty();
    assertThat(report.topExceptionCategories()).isEmpty();
    assertThat(report.readinessSignals()).anySatisfy(signal -> {
      assertThat(signal.label()).isEqualTo("Sample size");
      assertThat(signal.assessment()).isEqualTo("NO_DATA");
    });
    // Limitations and the safety statement are always present, even with no data.
    assertThat(report.limitations()).isNotEmpty();
    assertThat(report.safetyStatement()).isNotBlank();
  }

  @Test
  void evidenceReportIsTenantIsolated() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    service.recordShadowRun("INBOUND_DOCUMENT", UUID.randomUUID(), "EXTRACTION", "fix", "{}", new BigDecimal("0.9000"),
        "MARGIN_VIOLATION", new BigDecimal("10.00"), new BigDecimal("2.00"), true, false);

    TenantContext.setTenantId(tenantB);
    PilotShadowModeService.EvidenceReport report = service.evidenceReport();

    assertThat(report.tenantId()).isEqualTo(tenantB);
    assertThat(report.metrics().totalShadowRuns()).isZero();
    assertThat(report.metrics().estimatedMinutesSaved()).isEqualByComparingTo("0.00");
    assertThat(report.exceptionBreakdown()).isEmpty();
  }

  @Test
  void pilotMetricsAreTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    service.recordShadowRun("INBOUND_DOCUMENT", UUID.randomUUID(), "EXTRACTION", "fix", "{}", new BigDecimal("0.9000"),
        "MARGIN_VIOLATION", new BigDecimal("10.00"), new BigDecimal("2.00"), true, false);

    TenantContext.setTenantId(tenantB);
    PilotShadowModeService.PilotMetrics tenantBMetrics = service.metrics();

    assertThat(tenantBMetrics.totalShadowRuns()).isZero();
    assertThat(tenantBMetrics.estimatedMinutesSaved()).isEqualByComparingTo("0.00");
    assertThat(tenantBMetrics.exceptionCategoryCounts()).isEmpty();
    assertThat(service.exceptionBreakdown()).isEmpty();
  }
}
