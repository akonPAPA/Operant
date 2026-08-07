package com.orderpilot.aibot.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.aibot.api.model.AiJobResultResponse;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort;
import com.orderpilot.aibot.application.port.out.AibotAuditPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionRepositoryPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionVersionRepositoryPort;
import com.orderpilot.aibot.application.port.out.PublicIdGenerator;
import com.orderpilot.aibot.domain.aijob.AiJob;
import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import com.orderpilot.aibot.domain.aijob.AiJobStatus;
import com.orderpilot.aibot.domain.exception.BotDefinitionNotFoundException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PR #318 Slice 7 — preview result retrieval. The advisory outcome is returned via a safe DTO that
 * exposes only operator-facing fields, never the prompt, raw provider payload, request envelope,
 * lease owner, fencing token, internal tenant id, stack trace, or credentials.
 */
@ExtendWith(MockitoExtension.class)
class BotAiJobResultTest {

  private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String JOB = "aijob_1";

  @Mock private BotDefinitionRepositoryPort botDefinitionRepository;
  @Mock private BotDefinitionVersionRepositoryPort versionRepository;
  @Mock private AiJobRepositoryPort aiJobRepository;
  @Mock private AibotAuditPort auditPort;
  @Mock private PublicIdGenerator publicIdGenerator;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private UUID tenantId;

  @BeforeEach
  void setUp() {
    tenantId = UUID.randomUUID();
  }

  @Test
  void readyIntentJobExposesAdvisoryResult() {
    String resultJson =
        "{\"validationSummary\":\"validated\",\"intentKey\":\"HELP_REQUEST\",\"confidence\":0.90,"
            + "\"responseDraft\":\"How can I help?\",\"handoffSuggested\":false}";
    when(aiJobRepository.findByPublicIdAndTenantId(JOB, tenantId))
        .thenReturn(Optional.of(rehydrated(AiJobStatus.SUGGESTION_READY, resultJson, null)));

    AiJobResultResponse result = service().getAiJobResult(tenantId, JOB);

    assertThat(result.status()).isEqualTo("SUGGESTION_READY");
    assertThat(result.terminal()).isTrue();
    assertThat(result.intentKey()).isEqualTo("HELP_REQUEST");
    assertThat(result.confidence()).isEqualByComparingTo(new BigDecimal("0.90"));
    assertThat(result.responseDraft()).isEqualTo("How can I help?");
    assertThat(result.handoffSuggested()).isFalse();
    assertThat(result.validationSummary()).isEqualTo("validated");
    assertThat(result.failureClass()).isNull();
  }

  @Test
  void inFlightJobHasNoAdvisoryFields() {
    when(aiJobRepository.findByPublicIdAndTenantId(JOB, tenantId))
        .thenReturn(Optional.of(rehydrated(AiJobStatus.RUNNING, "{}", null)));

    AiJobResultResponse result = service().getAiJobResult(tenantId, JOB);

    assertThat(result.terminal()).isFalse();
    assertThat(result.intentKey()).isNull();
    assertThat(result.confidence()).isNull();
    assertThat(result.responseDraft()).isNull();
    assertThat(result.handoffSuggested()).isNull();
  }

  @Test
  void invalidJobSurfacesFailureClassNotResult() {
    when(aiJobRepository.findByPublicIdAndTenantId(JOB, tenantId))
        .thenReturn(Optional.of(rehydrated(AiJobStatus.INVALID, "{}", "OUTPUT_POLICY_REJECTED")));

    AiJobResultResponse result = service().getAiJobResult(tenantId, JOB);

    assertThat(result.terminal()).isTrue();
    assertThat(result.failureClass()).isEqualTo("OUTPUT_POLICY_REJECTED");
    assertThat(result.validationSummary()).isEqualTo("failed");
    assertThat(result.intentKey()).isNull();
    assertThat(result.responseDraft()).isNull();
  }

  @Test
  void missingJobIsNotFound() {
    when(aiJobRepository.findByPublicIdAndTenantId(JOB, tenantId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getAiJobResult(tenantId, JOB))
        .isInstanceOf(BotDefinitionNotFoundException.class);
  }

  // Structural no-leak contract: the public DTO must not carry any sensitive/internal field.
  @Test
  void resultDtoExposesNoSensitiveFields() {
    Set<String> forbidden =
        Set.of(
            "tenantid",
            "leaseowner",
            "fencingtoken",
            "requestjson",
            "requestfingerprint",
            "inputhash",
            "outputhash",
            "prompt",
            "provider",
            "providerpayload",
            "rawresponse",
            "credential",
            "apikey",
            "stacktrace");
    Set<String> components =
        Arrays.stream(AiJobResultResponse.class.getRecordComponents())
            .map(RecordComponent::getName)
            .map(String::toLowerCase)
            .collect(java.util.stream.Collectors.toSet());
    assertThat(components).doesNotContainAnyElementsOf(forbidden);
  }

  private AiJob rehydrated(AiJobStatus status, String resultJson, String failureClass) {
    AiJob job =
        new AiJob(JOB, tenantId, AiJobPurpose.BOT_INTENT_CLASSIFICATION,
            UUID.randomUUID(), "idem-1", "op-cap-m18.v1", NOW.minusSeconds(60));
    Instant completed = status.isTerminal() ? NOW : null;
    job.rehydrate(
        status, "gemini", "gemini-2.5-flash-lite", "op-cap-m18.v1", "bot-intent-classification-v1",
        "hash", "outhash", "{\"pendingInput\":\"secret prompt\"}", "fingerprint", "SYNTHETIC",
        resultJson, failureClass, 1, "worker-secret", NOW, 9L, null, NOW.minusSeconds(30), completed,
        UUID.randomUUID(), 3L);
    return job;
  }

  private BotManagementService service() {
    return new BotManagementService(
        botDefinitionRepository,
        versionRepository,
        aiJobRepository,
        auditPort,
        publicIdGenerator,
        objectMapper,
        CLOCK,
        new com.orderpilot.aibot.infrastructure.configuration.OperantAiProperties());
  }
}
