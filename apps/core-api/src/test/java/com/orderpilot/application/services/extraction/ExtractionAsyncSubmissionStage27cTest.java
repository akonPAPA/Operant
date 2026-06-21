package com.orderpilot.application.services.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage4Dtos.ExtractionRunRequest;
import com.orderpilot.api.dto.Stage4Dtos.ExtractionSubmissionResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.ProcessingJobService;
import com.orderpilot.application.services.runtime.AiWorkloadType;
import com.orderpilot.application.services.runtime.ModelTier;
import com.orderpilot.application.services.runtime.RuntimeControlDecision;
import com.orderpilot.application.services.runtime.RuntimeControlOutcome;
import com.orderpilot.application.services.runtime.RuntimeControlRequest;
import com.orderpilot.application.services.runtime.RuntimeControlService;
import com.orderpilot.application.services.runtime.RuntimeFeatureNotAvailableException;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.application.services.runtime.RuntimeGuardDecision;
import com.orderpilot.application.services.runtime.RuntimeGuardReasonCodes;
import com.orderpilot.application.services.runtime.RuntimeOperationType;
import com.orderpilot.application.services.runtime.RuntimeQuotaExceededException;
import com.orderpilot.application.services.runtime.RuntimeRateLimitedException;
import com.orderpilot.application.services.runtime.RuntimeWorkloadType;
import com.orderpilot.application.services.runtime.UsageMeterService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.usage.UsageEvent;
import com.orderpilot.domain.usage.UsageEventRepository;
import com.orderpilot.domain.usage.UsageEventType;
import com.orderpilot.domain.usage.UsageMetricType;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-27c — proves the document-extraction submission boundary
 * ({@code ExtractionPipelineService.submitForExtraction}) is truly asynchronous: an admitted submission
 * enqueues a durable {@code ProcessingJob} for the existing worker runtime and performs NO text/OCR/
 * semantic extraction in the request thread (no {@code ExtractionRun} is created). Denials throw the
 * existing stable mapped exception and enqueue nothing; needs-review/unsupported fail-close; duplicate
 * submissions reuse the existing job; and the trusted tenant + {@code DOCUMENT_EXTRACTION} workload are
 * resolved server-side. The internal executor ({@code runNow}) still performs the heavy work when the
 * worker/internal path runs it. {@link RuntimeControlService} is mocked so each outcome is driven
 * exactly (real quota/rate behavior is covered by the OP-CAP-16 guard tests + {@code RuntimeControlServiceTest}).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  ExtractionPipelineService.class,
  ProcessingJobService.class,
  ExtractionRunService.class,
  TextExtractionService.class,
  SemanticExtractionService.class,
  ConfidenceScoringService.class,
  ExtractionOutputSanitizer.class,
  RuleBasedMockSemanticExtractionProvider.class,
  MessageTextExtractionProvider.class,
  MockDocumentTextExtractionProvider.class,
  PromptInjectionGuardService.class,
  AuditEventService.class,
  UsageMeterService.class,
  JsonSupport.class,
  CoreConfiguration.class,
  ExtractionAsyncSubmissionStage27cTest.JacksonTestConfig.class
})
class ExtractionAsyncSubmissionStage27cTest {
  @Autowired private ExtractionPipelineService pipelineService;
  @Autowired private ChannelMessageRepository messageRepository;
  @Autowired private ProcessingJobRepository jobRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private ExtractionResultRepository resultRepository;
  @Autowired private UsageEventRepository usageEventRepository;
  @MockBean private RuntimeControlService runtimeControlService;

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

  // ----------------------------- fixtures -----------------------------

  private ChannelMessage newMessage(UUID tenantId, String externalId) {
    return messageRepository.save(
        new ChannelMessage(
            tenantId, "EMAIL", externalId, "thread-1", "buyer@example.test", "Buyer", null,
            "INBOUND", "TEXT", "Customer: Acme\nNeed 10 EA SKU-001 ship to Almaty by 2026-06-01",
            "{}", "QUEUED", Instant.parse("2026-05-24T00:00:00Z")));
  }

  private ExtractionRunRequest request(ChannelMessage message) {
    return new ExtractionRunRequest("CHANNEL_MESSAGE", message.getId(), null, "RULE_BASED");
  }

  private RuntimeControlDecision allowAsync(UUID tenantId) {
    return new RuntimeControlDecision(RuntimeControlOutcome.ALLOW_ASYNC, RuntimeGuardReasonCodes.ALLOWED,
        AiWorkloadType.DOCUMENT_EXTRACTION, ModelTier.MEDIUM, tenantId, null, true, null, false, false,
        true, false, 1, 200, 0L, "Allowed; handed to the asynchronous runtime.");
  }

  private RuntimeControlDecision nonAllow(UUID tenantId, RuntimeControlOutcome outcome, String message) {
    return new RuntimeControlDecision(outcome, "reason", AiWorkloadType.DOCUMENT_EXTRACTION,
        ModelTier.HUMAN_REVIEW, tenantId, null, true, null, false, false, false, true, 1,
        outcome == RuntimeControlOutcome.UNSUPPORTED ? 422 : 200, 0L, message);
  }

  private RuntimeGuardDecision deniedGuard(int status, String reason) {
    return new RuntimeGuardDecision(false, status, reason, RuntimeOperationType.AI_DOCUMENT_EXTRACTION,
        UsageMetricType.AI_INPUT_UNITS, 1L, 1L, 1L, 0L, 0L, null);
  }

  // ----------------------------- A. allowed async submission enqueues, does no extraction -----------------------------

  @Test
  void allowedSubmissionEnqueuesJobAndRunsNoExtractionInRequestThread() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-async");
    when(runtimeControlService.enforce(any())).thenReturn(allowAsync(tenantId));
    long jobsBefore = jobRepository.count();
    long runsBefore = runRepository.count();
    long resultsBefore = resultRepository.count();

    ExtractionSubmissionResponse response = pipelineService.submitForExtraction(request(message));

    assertThat(response.accepted()).isTrue();
    assertThat(response.async()).isTrue();
    assertThat(response.jobId()).isNotNull();
    assertThat(response.status()).isEqualTo("PENDING");
    assertThat(jobRepository.count()).isEqualTo(jobsBefore + 1);
    // No heavy work in the request thread: no ExtractionRun / ExtractionResult was created.
    assertThat(runRepository.count()).isEqualTo(runsBefore);
    assertThat(resultRepository.count()).isEqualTo(resultsBefore);
    // Safe acknowledgement only — no raw customer text leaks into the response.
    assertThat(response.message()).doesNotContain("Acme").doesNotContain("SKU-001");
    UsageEvent evidence = onlyRuntimeEvidence(tenantId);
    assertThat(evidence.getEventType()).isEqualTo(UsageEventType.RUNTIME_CONTROL_DECISION);
    assertThat(evidence.getUnits()).isEqualTo(1L);
    assertThat(evidence.getMetadataJson()).contains("\"outcome\":\"ALLOW_ASYNC\"");
    assertThat(evidence.getMetadataJson()).contains("\"downstreamInvoked\":true");
  }

  // ----------------------------- B. internal/worker execution still produces the run -----------------------------

  @Test
  void queuedSubmissionCanThenBeExecutedByInternalRunNow() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-exec");
    when(runtimeControlService.enforce(any())).thenReturn(allowAsync(tenantId));

    pipelineService.submitForExtraction(request(message));
    assertThat(runRepository.count()).isZero();

    // The worker/internal execution path performs the heavy work and creates the run + result.
    var run = pipelineService.runNow(request(message));

    assertThat(run.getTenantId()).isEqualTo(tenantId);
    assertThat(resultRepository.findFirstByTenantIdAndExtractionRunId(tenantId, run.getId())).isPresent();
  }

  // ----------------------------- C. deny before enqueue -----------------------------

  @Test
  void entitlementDenialThrowsAndEnqueuesNoJob() {
    assertDenialEnqueuesNoJob("msg-feat",
        new RuntimeFeatureNotAvailableException(deniedGuard(403, RuntimeGuardReasonCodes.FEATURE_NOT_ENTITLED)),
        RuntimeFeatureNotAvailableException.class);
  }

  @Test
  void quotaDenialThrowsAndEnqueuesNoJob() {
    assertDenialEnqueuesNoJob("msg-quota",
        new RuntimeQuotaExceededException(deniedGuard(403, RuntimeGuardReasonCodes.QUOTA_LIMIT_EXCEEDED)),
        RuntimeQuotaExceededException.class);
  }

  @Test
  void quotaDenialRecordsSafeEvidenceAndEnqueuesNoJob() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-quota-evidence");
    when(runtimeControlService.enforce(any()))
        .thenThrow(new RuntimeQuotaExceededException(deniedGuard(403, RuntimeGuardReasonCodes.QUOTA_LIMIT_EXCEEDED)));
    long jobsBefore = jobRepository.count();

    assertThatThrownBy(() -> pipelineService.submitForExtraction(request(message)))
        .isInstanceOf(RuntimeQuotaExceededException.class);

    assertThat(jobRepository.count()).isEqualTo(jobsBefore);
    UsageEvent evidence = onlyRuntimeEvidence(tenantId);
    assertThat(evidence.getUnits()).isZero();
    assertThat(evidence.getMetadataJson()).contains("\"outcome\":\"QUOTA_EXCEEDED\"");
    assertThat(evidence.getMetadataJson()).contains("\"rejectedCostUnits\":1");
    assertThat(evidence.getMetadataJson()).contains("\"downstreamInvoked\":false");
  }

  @Test
  void rateDenialThrowsAndEnqueuesNoJob() {
    assertDenialEnqueuesNoJob("msg-rate",
        new RuntimeRateLimitedException(deniedGuard(429, RuntimeGuardReasonCodes.RATE_LIMIT_EXCEEDED)),
        RuntimeRateLimitedException.class);
  }

  private void assertDenialEnqueuesNoJob(String ext, RuntimeException thrown, Class<? extends Throwable> type) {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, ext);
    when(runtimeControlService.enforce(any())).thenThrow(thrown);
    long jobsBefore = jobRepository.count();
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> pipelineService.submitForExtraction(request(message))).isInstanceOf(type);

    assertThat(jobRepository.count()).isEqualTo(jobsBefore);
    assertThat(runRepository.count()).isEqualTo(runsBefore);
  }

  // ----------------------------- D. dedup: duplicate submission does not create a second job -----------------------------

  @Test
  void duplicateSubmissionReusesExistingJobAndDoesNotEnqueueTwice() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-dup");
    when(runtimeControlService.enforce(any())).thenReturn(allowAsync(tenantId));
    long jobsBefore = jobRepository.count();

    ExtractionSubmissionResponse first = pipelineService.submitForExtraction(request(message));
    ExtractionSubmissionResponse second = pipelineService.submitForExtraction(request(message));

    assertThat(jobRepository.count()).isEqualTo(jobsBefore + 1);
    assertThat(second.jobId()).isEqualTo(first.jobId());
    assertThat(runRepository.count()).isZero();
    assertThat(runtimeEvidence(tenantId)).hasSize(1);
  }

  // ----------------------------- E. review/unsupported fail-close, no job -----------------------------

  @Test
  void needsReviewDecisionFailClosesAndEnqueuesNoJob() {
    assertNonAllowEnqueuesNoJob("msg-review", RuntimeControlOutcome.REQUIRES_REVIEW, "Workload routed to human review.");
  }

  @Test
  void unsupportedDecisionFailClosesAndEnqueuesNoJob() {
    assertNonAllowEnqueuesNoJob("msg-unsupported", RuntimeControlOutcome.UNSUPPORTED, "Workload could not be classified.");
  }

  @Test
  void aiDisabledDecisionRecordsSafeEvidenceAndEnqueuesNoJob() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage msg = newMessage(tenantId, "msg-ai-disabled");
    when(runtimeControlService.enforce(any()))
        .thenReturn(nonAllow(tenantId, RuntimeControlOutcome.DISABLED, "Denied; AI runtime work is disabled."));
    long jobsBefore = jobRepository.count();

    assertThatThrownBy(() -> pipelineService.submitForExtraction(request(msg)))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(jobRepository.count()).isEqualTo(jobsBefore);
    UsageEvent evidence = onlyRuntimeEvidence(tenantId);
    assertThat(evidence.getUnits()).isZero();
    assertThat(evidence.getMetadataJson()).contains("\"outcome\":\"DISABLED\"");
    assertThat(evidence.getMetadataJson()).contains("\"downstreamInvoked\":false");
  }

  private void assertNonAllowEnqueuesNoJob(String ext, RuntimeControlOutcome outcome, String message) {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage msg = newMessage(tenantId, ext);
    when(runtimeControlService.enforce(any())).thenReturn(nonAllow(tenantId, outcome, message));
    long jobsBefore = jobRepository.count();

    assertThatThrownBy(() -> pipelineService.submitForExtraction(request(msg)))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(jobRepository.count()).isEqualTo(jobsBefore);
    assertThat(runRepository.count()).isZero();
  }

  // ----------------------------- F. server-side authority -----------------------------

  @Test
  void submissionResolvesTrustedTenantAndDocumentExtractionWorkloadServerSide() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-authority");
    when(runtimeControlService.enforce(any())).thenReturn(allowAsync(tenantId));

    pipelineService.submitForExtraction(request(message));

    ArgumentCaptor<RuntimeControlRequest> captor = ArgumentCaptor.forClass(RuntimeControlRequest.class);
    verify(runtimeControlService).enforce(captor.capture());
    RuntimeControlRequest sent = captor.getValue();
    assertThat(sent.tenantId()).isEqualTo(tenantId);
    assertThat(sent.actorId()).isNull(); // trusted internal/system pipeline actor
    assertThat(sent.operationType()).isEqualTo(RuntimeOperationType.AI_DOCUMENT_EXTRACTION);
    assertThat(sent.featureType()).isEqualTo(RuntimeFeatureType.AI_DOCUMENT_EXTRACTION);
    assertThat(sent.classification().requestedType()).isEqualTo(AiWorkloadType.DOCUMENT_EXTRACTION);
  }

  @Test
  void clientProviderRuntimeFieldsDoNotControlAdmissionDecision() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-client-provider");
    when(runtimeControlService.enforce(any())).thenReturn(allowAsync(tenantId));
    ExtractionRunRequest spoofed = new ExtractionRunRequest(
        "CHANNEL_MESSAGE", message.getId(), UUID.randomUUID(), "client-selected-expensive-provider");

    ExtractionSubmissionResponse response = pipelineService.submitForExtraction(spoofed);

    ArgumentCaptor<RuntimeControlRequest> captor = ArgumentCaptor.forClass(RuntimeControlRequest.class);
    verify(runtimeControlService).enforce(captor.capture());
    RuntimeControlRequest sent = captor.getValue();
    assertThat(sent.tenantId()).isEqualTo(tenantId);
    assertThat(sent.actorId()).isNull();
    assertThat(sent.effectiveWorkloadType()).isEqualTo(RuntimeWorkloadType.AI_EXTRACTION);
    assertThat(sent.operationType()).isEqualTo(RuntimeOperationType.AI_DOCUMENT_EXTRACTION);
    assertThat(sent.featureType()).isEqualTo(RuntimeFeatureType.AI_DOCUMENT_EXTRACTION);
    assertThat(sent.requestedUnits()).isGreaterThanOrEqualTo(0L);
    assertThat(response.message()).doesNotContain("client-selected-expensive-provider");
  }

  private UsageEvent onlyRuntimeEvidence(UUID tenantId) {
    List<UsageEvent> evidence = runtimeEvidence(tenantId);
    assertThat(evidence).hasSize(1);
    return evidence.get(0);
  }

  private List<UsageEvent> runtimeEvidence(UUID tenantId) {
    return usageEventRepository.findAll().stream()
        .filter(e -> tenantId.equals(e.getTenantId()))
        .filter(e -> e.getEventType() == UsageEventType.RUNTIME_CONTROL_DECISION)
        .toList();
  }
}
