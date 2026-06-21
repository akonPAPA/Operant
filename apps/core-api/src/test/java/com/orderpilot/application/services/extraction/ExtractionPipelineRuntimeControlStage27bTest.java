package com.orderpilot.application.services.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage4Dtos.ExtractionRunRequest;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.runtime.AiWorkloadType;
import com.orderpilot.application.services.runtime.ModelTier;
import com.orderpilot.application.services.runtime.RuntimeControlDecision;
import com.orderpilot.application.services.runtime.RuntimeControlOutcome;
import com.orderpilot.application.services.runtime.RuntimeControlRequest;
import com.orderpilot.application.services.runtime.RuntimeControlService;
import com.orderpilot.application.services.runtime.RuntimeFeatureNotAvailableException;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.application.services.runtime.RuntimeGuardDecision;
import com.orderpilot.application.services.runtime.RuntimeOperationType;
import com.orderpilot.application.services.runtime.RuntimeGuardReasonCodes;
import com.orderpilot.application.services.runtime.RuntimeQuotaExceededException;
import com.orderpilot.application.services.runtime.RuntimeRateLimitedException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.usage.UsageMetricType;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
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
 * OP-CAP-27b — proves the document-extraction submission boundary ({@code
 * ExtractionPipelineService.runNow}) is now routed through the centralized {@link RuntimeControlService}
 * decision spine. The control service is mocked so each outcome can be driven exactly, without depending
 * on the real quota/rate stores (those are covered by {@code ExtractionPipelineGuardStage16*Test} and
 * {@code RuntimeControlServiceTest}). Proven: an allowed decision lets the run proceed; entitlement /
 * quota / rate denials throw the existing stable mapped exception and create no run or extraction work;
 * a non-allowed (needs-review / unsupported) decision fail-closes with no run; and the boundary passes
 * the trusted server-side tenant + {@code DOCUMENT_EXTRACTION} workload (never a client-supplied
 * authority) into the decision.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  ExtractionPipelineService.class,
  com.orderpilot.application.services.ProcessingJobService.class,
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
  JsonSupport.class,
  CoreConfiguration.class,
  ExtractionPipelineRuntimeControlStage27bTest.JacksonTestConfig.class
})
class ExtractionPipelineRuntimeControlStage27bTest {
  @Autowired private ExtractionPipelineService pipelineService;
  @Autowired private ChannelMessageRepository messageRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private ExtractionResultRepository resultRepository;
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

  private ExtractionRunRequest runRequest(ChannelMessage message) {
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

  // ----------------------------- allowed flow -----------------------------

  @Test
  void allowedDecisionLetsRunProceedAndCreatesRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-ok");
    when(runtimeControlService.enforce(any())).thenReturn(allowAsync(tenantId));
    long runsBefore = runRepository.count();

    var run = pipelineService.runNow(runRequest(message));

    assertThat(runRepository.count()).isEqualTo(runsBefore + 1);
    assertThat(resultRepository.findFirstByTenantIdAndExtractionRunId(tenantId, run.getId())).isPresent();
  }

  // ----------------------------- denial before work -----------------------------

  @Test
  void entitlementDenialThrowsAndCreatesNoRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-feat");
    when(runtimeControlService.enforce(any()))
        .thenThrow(new RuntimeFeatureNotAvailableException(deniedGuard(403, RuntimeGuardReasonCodes.FEATURE_NOT_ENTITLED)));
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> pipelineService.runNow(runRequest(message)))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);
    assertThat(runRepository.count()).isEqualTo(runsBefore);
  }

  @Test
  void quotaDenialThrowsAndCreatesNoRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-quota");
    when(runtimeControlService.enforce(any()))
        .thenThrow(new RuntimeQuotaExceededException(deniedGuard(403, RuntimeGuardReasonCodes.QUOTA_LIMIT_EXCEEDED)));
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> pipelineService.runNow(runRequest(message)))
        .isInstanceOf(RuntimeQuotaExceededException.class);
    assertThat(runRepository.count()).isEqualTo(runsBefore);
  }

  @Test
  void rateDenialThrowsAndCreatesNoRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-rate");
    when(runtimeControlService.enforce(any()))
        .thenThrow(new RuntimeRateLimitedException(deniedGuard(429, RuntimeGuardReasonCodes.RATE_LIMIT_EXCEEDED)));
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> pipelineService.runNow(runRequest(message)))
        .isInstanceOf(RuntimeRateLimitedException.class);
    assertThat(runRepository.count()).isEqualTo(runsBefore);
  }

  // ----------------------------- non-allowed decisions fail closed -----------------------------

  @Test
  void needsReviewDecisionFailClosesWithNoRunOrExtraction() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-review");
    when(runtimeControlService.enforce(any()))
        .thenReturn(nonAllow(tenantId, RuntimeControlOutcome.REQUIRES_REVIEW, "Workload routed to human review."));
    long runsBefore = runRepository.count();
    long resultsBefore = resultRepository.count();

    assertThatThrownBy(() -> pipelineService.runNow(runRequest(message)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(runRepository.count()).isEqualTo(runsBefore);
    assertThat(resultRepository.count()).isEqualTo(resultsBefore);
  }

  @Test
  void unsupportedDecisionFailClosesWithNoRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-unsupported");
    when(runtimeControlService.enforce(any()))
        .thenReturn(nonAllow(tenantId, RuntimeControlOutcome.UNSUPPORTED, "Workload could not be classified."));
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> pipelineService.runNow(runRequest(message)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(runRepository.count()).isEqualTo(runsBefore);
  }

  // ----------------------------- server-side authority -----------------------------

  @Test
  void submissionResolvesTrustedTenantAndDocumentExtractionWorkloadServerSide() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-authority");
    when(runtimeControlService.enforce(any())).thenReturn(allowAsync(tenantId));

    pipelineService.runNow(runRequest(message));

    ArgumentCaptor<RuntimeControlRequest> captor = ArgumentCaptor.forClass(RuntimeControlRequest.class);
    verify(runtimeControlService).enforce(captor.capture());
    RuntimeControlRequest sent = captor.getValue();
    assertThat(sent.tenantId()).isEqualTo(tenantId);
    assertThat(sent.actorId()).isNull(); // trusted internal/system pipeline actor
    assertThat(sent.operationType()).isEqualTo(RuntimeOperationType.AI_DOCUMENT_EXTRACTION);
    assertThat(sent.featureType()).isEqualTo(RuntimeFeatureType.AI_DOCUMENT_EXTRACTION);
    assertThat(sent.classification().requestedType()).isEqualTo(AiWorkloadType.DOCUMENT_EXTRACTION);
    assertThat(sent.requestedUnits()).isGreaterThanOrEqualTo(0L);
    assertThat(sent.idempotencyKey()).isNull();
  }
}
