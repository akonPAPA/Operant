package com.orderpilot.application.services.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.common.tenant.TenantContextMissingException;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.pilot.HumanCorrection;
import com.orderpilot.domain.pilot.HumanCorrectionRepository;
import com.orderpilot.domain.pilot.ShadowRun;
import com.orderpilot.domain.pilot.ShadowRunRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
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
@Import({PilotShadowModeService.class, AuditEventService.class, CoreConfiguration.class})
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
}
