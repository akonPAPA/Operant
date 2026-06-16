package com.orderpilot.application.services.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.CommandCenterDtos.CommandCenterMetricDto;
import com.orderpilot.api.dto.CommandCenterDtos.CommandCenterSummaryDto;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.integration.OutboxEvent;
import com.orderpilot.domain.integration.OutboxEventRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.reconciliation.ReconciliationCase;
import com.orderpilot.domain.reconciliation.ReconciliationCaseRepository;
import com.orderpilot.domain.reconciliation.ReconciliationSeverity;
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

/** OP-CAP-21 — tenant isolation, bounded preview, and honest-state coverage for the command center. */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommandCenterReadService.class, CoreConfiguration.class})
class CommandCenterReadServiceTest {
  @Autowired private CommandCenterReadService service;
  @Autowired private ExceptionCaseRepository exceptionCaseRepository;
  @Autowired private ProcessingJobRepository processingJobRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ReconciliationCaseRepository reconciliationCaseRepository;

  private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void summaryIsTenantIsolated() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    openCase(tenantA, "CASE-A1", "HIGH");
    openCase(tenantB, "CASE-B1", "HIGH");
    reconciliationCaseRepository.save(new ReconciliationCase(tenantA, UUID.randomUUID(), UUID.randomUUID(),
        new BigDecimal("116"), new BigDecimal("100"), new BigDecimal("-16"), ReconciliationSeverity.HIGH, "[\"low\"]", NOW));
    auditEventRepository.save(new AuditEvent(tenantA, UUID.randomUUID(), "DRAFT_QUOTE_MARKED_READY", "DRAFT_QUOTE", UUID.randomUUID().toString(), "{\"secret\":\"redact-me\"}", NOW));
    auditEventRepository.save(new AuditEvent(tenantB, UUID.randomUUID(), "DRAFT_ORDER_MARKED_READY", "DRAFT_ORDER", UUID.randomUUID().toString(), "{}", NOW));

    TenantContext.setTenantId(tenantA);
    CommandCenterSummaryDto summary = service.summary();

    assertThat(summary.tenantId()).isEqualTo(tenantA);
    assertThat(metric(summary, "pendingReviews").value()).isEqualTo(1);
    assertThat(metric(summary, "highRiskCases").value()).isEqualTo(1);
    assertThat(summary.workQueue().items()).hasSize(1);
    assertThat(summary.workQueue().items().get(0).caseNumber()).isEqualTo("CASE-A1");
    assertThat(summary.reconciliation().available()).isTrue();
    assertThat(summary.reconciliation().openCases()).isEqualTo(1);
    assertThat(summary.auditTimeline().items()).hasSize(1);
    assertThat(summary.auditTimeline().items().get(0).action()).isEqualTo("DRAFT_QUOTE_MARKED_READY");
  }

  @Test
  void auditTimelinePreviewExcludesSensitiveMetadataPayload() {
    UUID tenantId = UUID.randomUUID();
    auditEventRepository.save(new AuditEvent(tenantId, UUID.randomUUID(), "ANY_ACTION", "ENTITY", "id-1", "{\"pan\":\"4111111111111111\"}", NOW));

    TenantContext.setTenantId(tenantId);
    CommandCenterSummaryDto summary = service.summary();

    // The DTO is structurally incapable of carrying the metadata blob.
    boolean hasMetadataAccessor =
        java.util.Arrays.stream(summary.auditTimeline().items().get(0).getClass().getMethods())
            .anyMatch(m -> m.getName().toLowerCase().contains("metadata") || m.getName().toLowerCase().contains("payload"));
    assertThat(hasMetadataAccessor).isFalse();
  }

  @Test
  void runtimeAndOutboxFailureStatesAreRepresented() {
    UUID tenantId = UUID.randomUUID();
    ProcessingJob failed = processingJobRepository.save(new ProcessingJob(tenantId, "MESSAGE_PROCESSING", "CHANNEL_MESSAGE", UUID.randomUUID(), 5, NOW));
    ReflectionTestUtils.setField(failed, "status", "FAILED");
    processingJobRepository.save(failed);
    outboxEventRepository.save(new OutboxEvent(tenantId, "DRAFT_QUOTE", UUID.randomUUID(), "DRAFT_QUOTE_READY", "{}", NOW));

    TenantContext.setTenantId(tenantId);
    CommandCenterSummaryDto summary = service.summary();

    assertThat(summary.runtime().available()).isTrue();
    assertThat(summary.runtime().failedJobs()).isEqualTo(1);
    assertThat(summary.runtime().degraded()).isTrue();
    assertThat(summary.outbox().available()).isTrue();
    assertThat(summary.outbox().pendingEvents()).isEqualTo(1);
    assertThat(summary.outbox().degraded()).isTrue();
    assertThat(metric(summary, "jobsFailed").value()).isEqualTo(1);
  }

  @Test
  void emptyTenantReturnsHonestUnavailableStatesNotFabricatedValues() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    CommandCenterSummaryDto summary = service.summary();

    assertThat(summary.runtime().available()).isFalse();
    assertThat(summary.outbox().available()).isFalse();
    assertThat(summary.workQueue().items()).isEmpty();
    assertThat(summary.auditTimeline().items()).isEmpty();
    CommandCenterMetricDto readiness = metric(summary, "automationReadiness");
    assertThat(readiness.available()).isFalse();
    assertThat(readiness.value()).isZero();
  }

  @Test
  void workQueuePreviewIsBounded() {
    UUID tenantId = UUID.randomUUID();
    for (int i = 0; i < 25; i++) {
      openCase(tenantId, "CASE-" + i, "MEDIUM");
    }

    TenantContext.setTenantId(tenantId);
    CommandCenterSummaryDto summary = service.summary();

    assertThat(summary.workQueue().items()).hasSize(20);
    assertThat(summary.workQueue().openTotal()).isEqualTo(25);
    assertThat(summary.workQueue().partial()).isTrue();
  }

  private void openCase(UUID tenantId, String caseNumber, String severity) {
    exceptionCaseRepository.save(new ExceptionCase(tenantId, caseNumber, "VALIDATION_RUN", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), null, "Review", "REVIEW_REQUIRED", "HIGH", severity, "summary", NOW));
  }

  private CommandCenterMetricDto metric(CommandCenterSummaryDto summary, String key) {
    return summary.metrics().stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
  }
}
