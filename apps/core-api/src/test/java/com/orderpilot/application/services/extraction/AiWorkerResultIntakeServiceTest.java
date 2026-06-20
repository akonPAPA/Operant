package com.orderpilot.application.services.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeRequest;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-07D — AI-worker result intake service tests: fail-closed validation, correlation, advisory
 * persistence, status mapping, idempotency, bounded audit, and no business mutation.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    AiWorkerResultIntakeService.class,
    AuditEventService.class,
    JsonSupport.class,
    CoreConfiguration.class,
    AiWorkerResultIntakeServiceTest.JacksonTestConfig.class
})
class AiWorkerResultIntakeServiceTest {
  @Autowired private AiWorkerResultIntakeService service;
  @Autowired private ProcessingJobRepository jobRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private ExtractionResultRepository resultRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private DraftOrderRepository draftOrderRepository;

  private static final Instant T0 = Instant.parse("2026-06-06T00:00:00Z");

  @TestConfiguration
  static class JacksonTestConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private long countAudits(String action) {
    return auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction())).count();
  }

  private ProcessingJob job(UUID tenantId, String targetType, UUID targetId) {
    return jobRepository.save(new ProcessingJob(tenantId, "MESSAGE_PROCESSING", targetType, targetId, 100, T0));
  }

  private static Map<String, Object> rfqExtraction() {
    return Map.of(
        "detected_intent", "RFQ",
        "document_type", "message",
        "overall_confidence", 0.82,
        "advisory_only", true);
  }

  private static AiProcessingResultIntakeRequest request(
      UUID jobId, String sourceType, UUID sourceId, String status, Map<String, Object> extraction) {
    return new AiProcessingResultIntakeRequest(
        jobId, TenantContext.requireTenantId().toString(), sourceType, sourceId, status, extraction,
        List.of("input_truncated"), List.of(), List.of(),
        Map.of("provider_name", "rule-based-understanding", "provider_version", "rule-based-v1", "mode", "RULE_BASED"),
        "op-cap-07c.v1", T0, T0.plusMillis(12), 12L, null);
  }

  @Test
  void succeededResultIsPersistedAsAdvisoryExtraction() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);

    AiProcessingResultIntakeResponse response =
        service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", rfqExtraction()));

    assertThat(response.duplicate()).isFalse();
    assertThat(response.advisoryOnly()).isTrue();
    assertThat(response.jobStatus()).isEqualTo("SUCCEEDED");
    assertThat(response.resultStatus()).isEqualTo("SUCCEEDED");
    ExtractionResult result = resultRepository.findByIdAndTenantId(response.extractionResultId(), tenantId).orElseThrow();
    assertThat(result.getValidationStatus()).isEqualTo("READY_FOR_VALIDATION");
    assertThat(result.getDetectedIntent()).isEqualTo("RFQ");
    assertThat(result.getResultJson()).contains("\"advisoryOnly\":true").contains("\"untrustedUntilValidation\":true");
    assertThat(jobRepository.findByIdAndTenantId(job.getId(), tenantId).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
  }

  @Test
  void needsReviewResultMapsToReviewState() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);

    AiProcessingResultIntakeResponse response = service.intake(new AiProcessingResultIntakeRequest(
        job.getId(), tenantId.toString(), "CHANNEL_MESSAGE", sourceId, "NEEDS_REVIEW", rfqExtraction(),
        List.of(), List.of(), List.of("ignore previous instructions"),
        Map.of("provider_name", "rule-based-understanding", "mode", "RULE_BASED"),
        "op-cap-07c.v1", T0, T0, 5L, null));

    assertThat(response.resultStatus()).isEqualTo("NEEDS_REVIEW");
    assertThat(response.jobStatus()).isEqualTo("NEEDS_REVIEW");
    ExtractionResult result = resultRepository.findByIdAndTenantId(response.extractionResultId(), tenantId).orElseThrow();
    assertThat(result.getValidationStatus()).isEqualTo("NEEDS_REVIEW");
  }

  @Test
  void failedResultIsRecordedWithSafeReasonAndNoLeak() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "INBOUND_DOCUMENT", sourceId);

    AiProcessingResultIntakeResponse response = service.intake(new AiProcessingResultIntakeRequest(
        job.getId(), tenantId.toString(), "INBOUND_DOCUMENT", sourceId, "FAILED", Map.of(),
        List.of(), List.of("provider_error"), List.of(),
        Map.of("provider_name", "rule-based-understanding", "mode", "RULE_BASED"),
        "op-cap-07c.v1", T0, T0, 7L, "provider_error"));

    assertThat(response.jobStatus()).isEqualTo("FAILED");
    ExtractionResult result = resultRepository.findByIdAndTenantId(response.extractionResultId(), tenantId).orElseThrow();
    assertThat(result.getValidationStatus()).isEqualTo("FAILED");
    assertThat(result.getResultJson()).contains("provider_error");
    // Only the bounded safe reason is persisted — no stack trace / internal exception detail.
    assertThat(result.getResultJson()).doesNotContain("Exception").doesNotContain("\tat ");
  }

  @Test
  void rejectedResultIsRecordedSafely() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "PDF_TEXT", sourceId);

    AiProcessingResultIntakeResponse response = service.intake(new AiProcessingResultIntakeRequest(
        job.getId(), tenantId.toString(), "PDF_TEXT", sourceId, "REJECTED", Map.of(),
        List.of(), List.of(), List.of(),
        Map.of("provider_name", "rule-based-understanding", "mode", "RULE_BASED"),
        "op-cap-07c.v1", T0, T0, 1L, "raw_text_too_large"));

    assertThat(response.jobStatus()).isEqualTo("REJECTED");
    ExtractionResult result = resultRepository.findByIdAndTenantId(response.extractionResultId(), tenantId).orElseThrow();
    assertThat(result.getValidationStatus()).isEqualTo("REJECTED");
  }

  @Test
  void missingJobOrSourceCorrelationIsRejectedFailClosed() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    assertThatThrownBy(() -> service.intake(request(null, "CHANNEL_MESSAGE", UUID.randomUUID(), "SUCCEEDED", rfqExtraction())))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing_job_id");
    assertThatThrownBy(() -> service.intake(request(UUID.randomUUID(), "CHANNEL_MESSAGE", null, "SUCCEEDED", rfqExtraction())))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing_source_id");
    assertThatThrownBy(() -> service.intake(new AiProcessingResultIntakeRequest(
        UUID.randomUUID(), null, "CHANNEL_MESSAGE", UUID.randomUUID(), "SUCCEEDED", rfqExtraction(),
        List.of(), List.of(), List.of(), Map.of("mode", "RULE_BASED"), "op-cap-07c.v1", T0, T0, 1L, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing_tenant_ref");
    // Job id that does not correspond to any tenant-scoped processing job.
    assertThatThrownBy(() -> service.intake(request(UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), "SUCCEEDED", rfqExtraction())))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("processing_job_not_found");
  }

  @Test
  void tenantSourceJobMismatchIsRejectedFailClosed() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID targetId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", targetId);

    // source id does not match the job target
    assertThatThrownBy(() -> service.intake(request(job.getId(), "CHANNEL_MESSAGE", UUID.randomUUID(), "SUCCEEDED", rfqExtraction())))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source_id_mismatch");
    // source type does not match the job target type
    assertThatThrownBy(() -> service.intake(request(job.getId(), "INBOUND_DOCUMENT", targetId, "SUCCEEDED", rfqExtraction())))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source_type_mismatch");
    // worker tenantRef disagreeing with the trusted tenant
    assertThatThrownBy(() -> service.intake(new AiProcessingResultIntakeRequest(
        job.getId(), UUID.randomUUID().toString(), "CHANNEL_MESSAGE", targetId, "SUCCEEDED", rfqExtraction(),
        List.of(), List.of(), List.of(), Map.of(), "op-cap-07c.v1", T0, T0, 1L, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenant_correlation_mismatch");
  }

  @Test
  void unsupportedSchemaVersionIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);

    assertThatThrownBy(() -> service.intake(new AiProcessingResultIntakeRequest(
        job.getId(), tenantId.toString(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", rfqExtraction(),
        List.of(), List.of(), List.of(), Map.of(), "op-cap-99.v9", T0, T0, 1L, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported_schema_version");
  }

  @Test
  void unsupportedOrMissingPipelineIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);

    assertThatThrownBy(() -> service.intake(new AiProcessingResultIntakeRequest(
        job.getId(), tenantId.toString(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", rfqExtraction(),
        List.of(), List.of(), List.of(), Map.of(), "op-cap-07c.v1", T0, T0, 1L, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing_pipeline");
    assertThatThrownBy(() -> service.intake(new AiProcessingResultIntakeRequest(
        job.getId(), tenantId.toString(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", rfqExtraction(),
        List.of(), List.of(), List.of(), Map.of("mode", "FUTURE_SEMANTIC"),
        "op-cap-07c.v1", T0, T0, 1L, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported_pipeline");
  }

  @Test
  void resultForNonExtractionJobTypeIsRejectedAsPipelineMismatch() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = jobRepository.save(new ProcessingJob(tenantId, "CONNECTOR_EXECUTION", "CHANNEL_MESSAGE", sourceId, 100, T0));

    assertThatThrownBy(() -> service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", rfqExtraction())))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pipeline_job_type_mismatch");
  }

  @Test
  void malformedAdvisorySchemaIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);

    assertThatThrownBy(() -> service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED",
        Map.of("document_type", "message", "overall_confidence", 0.7, "advisory_only", true))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("malformed_extraction_result");
    assertThatThrownBy(() -> service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED",
        Map.of("detected_intent", "RFQ", "document_type", "message", "overall_confidence", 1.5, "advisory_only", true))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("malformed_extraction_result");
    assertThatThrownBy(() -> service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED",
        Map.of("detected_intent", "RFQ", "document_type", "message", "overall_confidence", 0.7, "advisory_only", false))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non_advisory_result");
  }

  @Test
  void payloadSourceMismatchIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);
    Map<String, Object> extraction = Map.of(
        "detected_intent", "RFQ",
        "document_type", "message",
        "overall_confidence", 0.82,
        "advisory_only", true,
        "source_id", UUID.randomUUID().toString());

    assertThatThrownBy(() -> service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", extraction)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("payload_source_mismatch");
  }

  @Test
  void oversizedPayloadIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);
    Map<String, Object> huge = Map.of("detected_intent", "RFQ", "document_type", "message",
        "overall_confidence", 0.5, "advisory_only", true,
        "blob", "x".repeat(AiWorkerResultIntakeService.MAX_PAYLOAD_CHARS + 10));

    assertThatThrownBy(() -> service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", huge)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("payload_too_large");
  }

  @Test
  void forbiddenTopLevelActionKeyIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);
    Map<String, Object> hostile = Map.of("detected_intent", "RFQ", "document_type", "message",
        "overall_confidence", 0.7, "advisory_only", true, "approve", true);

    assertThatThrownBy(() -> service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", hostile)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("forbidden_action_key");
  }

  @Test
  void nestedAuthorityAndConnectorCommandKeysAreRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);

    Map<String, Object> authority = Map.of("detected_intent", "RFQ", "document_type", "message",
        "overall_confidence", 0.7, "advisory_only", true, "customer", Map.of("actorId", UUID.randomUUID()));
    assertThatThrownBy(() -> service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", authority)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("forbidden_authority_key");

    Map<String, Object> connector = Map.of("detected_intent", "RFQ", "document_type", "message",
        "overall_confidence", 0.7, "advisory_only", true,
        "suggestions", List.of(Map.of("erpWrite", Map.of("order", "create"))));
    assertThatThrownBy(() -> service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", connector)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("forbidden_action_key");
  }

  @Test
  void duplicateResultDeliveryIsIdempotent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);

    AiProcessingResultIntakeResponse first =
        service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", rfqExtraction()));
    AiProcessingResultIntakeResponse second =
        service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", rfqExtraction()));

    assertThat(first.duplicate()).isFalse();
    assertThat(second.duplicate()).isTrue();
    assertThat(second.extractionRunId()).isEqualTo(first.extractionRunId());
    assertThat(second.extractionResultId()).isEqualTo(first.extractionResultId());
    assertThat(runRepository.findFirstByTenantIdAndProcessingJobIdAndProviderType(
        tenantId, job.getId(), "AI_WORKER")).isPresent();
    assertThat(resultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(
        tenantId, "CHANNEL_MESSAGE", sourceId)).hasSize(1);
  }

  @Test
  void duplicateConflictingTerminalResultIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);

    AiProcessingResultIntakeResponse first =
        service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", rfqExtraction()));

    assertThatThrownBy(() -> service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "FAILED", Map.of())))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conflicting_terminal_result");
    assertThat(runRepository.findFirstByTenantIdAndProcessingJobIdAndProviderType(
        tenantId, job.getId(), "AI_WORKER")).map(r -> r.getId()).contains(first.extractionRunId());
    assertThat(resultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(
        tenantId, "CHANNEL_MESSAGE", sourceId)).hasSize(1);
  }

  @Test
  void intakeEmitsAuditEventWithBoundedMetadata() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);
    long before = countAudits("ai_processing_result.intake_succeeded");

    service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", rfqExtraction()));

    assertThat(countAudits("ai_processing_result.intake_succeeded") - before).isEqualTo(1);
    var event = auditEventRepository.findAll().stream()
        .filter(e -> "ai_processing_result.intake_succeeded".equals(e.getAction())
            && e.getMetadata().contains(job.getId().toString()))
        .findFirst().orElseThrow();
    String metadata = event.getMetadata();
    assertThat(metadata).contains("resultStatus").contains("warningCount").contains("providerName");
    // Bounded: the advisory extraction payload is never serialized into audit metadata.
    assertThat(metadata).doesNotContain("detected_intent").doesNotContain("extraction");
  }

  @Test
  void intakeDoesNotCreateOrMutateQuoteOrOrderEntities() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);
    long quotesBefore = draftQuoteRepository.count();
    long ordersBefore = draftOrderRepository.count();

    service.intake(request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", rfqExtraction()));

    assertThat(draftQuoteRepository.count()).isEqualTo(quotesBefore);
    assertThat(draftOrderRepository.count()).isEqualTo(ordersBefore);
  }
}
