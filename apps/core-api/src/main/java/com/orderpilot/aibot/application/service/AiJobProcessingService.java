package com.orderpilot.aibot.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort.ClaimedAiJob;
import com.orderpilot.aibot.application.port.out.AiProviderPort;
import com.orderpilot.aibot.application.port.out.AibotAuditPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionVersionRepositoryPort;
import com.orderpilot.aibot.domain.aijob.AiJob;
import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import com.orderpilot.aibot.domain.aijob.AiJobStatus;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionConfiguration;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionVersion;
import com.orderpilot.aibot.domain.botdefinition.BotIntentKey;
import com.orderpilot.aibot.domain.capability.BotCapabilityKey;
import com.orderpilot.aibot.infrastructure.configuration.OperantAiProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiJobProcessingService {
  /** Explicit machine actor for background AI job audit; never null. */
  static final UUID AIBOT_WORKER_ACTOR_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

  private final AiJobRepositoryPort aiJobRepository;
  private final BotDefinitionVersionRepositoryPort versionRepository;
  private final AiProviderPort aiProviderPort;
  private final AiOutputValidator outputValidator;
  private final AibotAuditPort auditPort;
  private final OperantAiProperties aiProperties;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public AiJobProcessingService(
      AiJobRepositoryPort aiJobRepository,
      BotDefinitionVersionRepositoryPort versionRepository,
      AiProviderPort aiProviderPort,
      AiOutputValidator outputValidator,
      AibotAuditPort auditPort,
      OperantAiProperties aiProperties,
      ObjectMapper objectMapper,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.aiJobRepository = aiJobRepository;
    this.versionRepository = versionRepository;
    this.aiProviderPort = aiProviderPort;
    this.outputValidator = outputValidator;
    this.auditPort = auditPort;
    this.aiProperties = aiProperties;
    this.objectMapper = objectMapper;
    this.clock = clock;
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transactionTemplate = template;
  }

  public void processClaimedJob(ClaimedAiJob claim) {
    AiJob claimed = claim.job();
    claimed.assertLeaseOwnership(claim.leaseOwner(), claim.fencingToken());
    if (claimed.status() != AiJobStatus.LEASED) {
      throw new IllegalStateException("expected_leased");
    }
    if (!aiProperties.isEnabled()) {
      transactionTemplate.executeWithoutResult(
          status -> markTerminalFailure(claimed.tenantId(), claimed.publicId(), claim, "PROVIDER_DISABLED"));
      return;
    }

    AiJob running =
        transactionTemplate.execute(
            status -> {
              AiJob job =
                  aiJobRepository
                      .findByPublicIdAndTenantId(claimed.publicId(), claimed.tenantId())
                      .orElseThrow(() -> new IllegalStateException("ai_job_missing"));
              job.assertLeaseOwnership(claim.leaseOwner(), claim.fencingToken());
              if (job.status() != AiJobStatus.LEASED) {
                throw new IllegalStateException("expected_leased");
              }
              job.markRunning(aiProperties.getProvider(), "configured", clock.instant());
              return aiJobRepository.save(job);
            });

    String requestEnvelope = running.requestJson();
    String minimizedUserContent = readPendingInput(requestEnvelope);
    AiProviderPort.ProviderResult providerResult;
    try {
      providerResult =
          aiProviderPort.generateStructured(
              new AiProviderPort.AiProviderRequest(
                  running.purpose(),
                  "aibot-system-policy-v1",
                  running.requestSchemaVersion(),
                  minimizedUserContent,
                  Arrays.stream(BotIntentKey.values())
                      .filter(k -> k != BotIntentKey.UNSUPPORTED)
                      .map(Enum::name)
                      .toList(),
                  Arrays.stream(BotCapabilityKey.values()).map(Enum::name).toList(),
                  readLocale(requestEnvelope),
                  running.publicId(),
                  aiProperties.getMaximumOutputTokens()));
    } catch (RuntimeException ex) {
      handleProviderFailure(claim, mapFailure(ex));
      throw ex;
    }

    transactionTemplate.executeWithoutResult(
        status -> completeWithProviderResult(claim, providerResult));
  }

  private void completeWithProviderResult(
      ClaimedAiJob claim, AiProviderPort.ProviderResult providerResult) {
    UUID tenantId = claim.job().tenantId();
    String jobPublicId = claim.job().publicId();
    AiJob job =
        aiJobRepository
            .findByPublicIdAndTenantId(jobPublicId, tenantId)
            .orElseThrow(() -> new IllegalStateException("ai_job_missing"));
    job.assertLeaseOwnership(claim.leaseOwner(), claim.fencingToken());
    if (job.status() != AiJobStatus.RUNNING) {
      throw new IllegalStateException("expected_running");
    }
    BotDefinitionVersion version =
        versionRepository
            .findByIdAndTenantId(job.botDefinitionVersionId(), tenantId)
            .orElseThrow(() -> new IllegalStateException("bot_version_missing"));
    try {
      String outputHash = AiRequestFingerprint.sha256Hex(providerResult.normalizedResponseText());
      job.markOutputReceived(outputHash, clock.instant());
      if (job.purpose() == AiJobPurpose.BOT_DEFINITION_GENERATION) {
        BotDefinitionConfiguration configuration =
            outputValidator.parseBotDefinition(providerResult.normalizedResponseText());
        ObjectNode validation = objectMapper.createObjectNode();
        validation.put("validationSummary", "validated");
        validation.putArray("findings");
        ObjectNode provenance = objectMapper.createObjectNode();
        provenance.put("provider", providerResult.provider());
        provenance.put("model", providerResult.model());
        provenance.put("schemaVersion", BotDefinitionConfiguration.SCHEMA_V1);
        version.applyValidatedConfiguration(
            configuration, validation.toString(), provenance.toString(), clock.instant());
        versionRepository.save(version);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("validationSummary", "validated");
        job.markSuggestionReady(
            result.toString(), BotDefinitionConfiguration.SCHEMA_V1, clock.instant());
      } else {
        AiOutputValidator.IntentClassification classification =
            outputValidator.parseIntentClassification(providerResult.normalizedResponseText());
        ObjectNode result = objectMapper.createObjectNode();
        result.put("validationSummary", "validated");
        result.put("intentKey", classification.intentKey());
        result.put("confidence", classification.confidence());
        result.put("responseDraft", classification.responseDraft());
        result.put("handoffSuggested", classification.handoffSuggested());
        job.markSuggestionReady(result.toString(), AiOutputValidator.INTENT_SCHEMA_V1, clock.instant());
      }
      aiJobRepository.save(job);
      auditPort.record(
          tenantId,
          AIBOT_WORKER_ACTOR_ID,
          "AIBOT_AI_JOB_READY",
          "AIBOT_AI_JOB",
          job.publicId(),
          "{\"status\":\"SUGGESTION_READY\",\"actorClass\":\"MACHINE_WORKER\"}");
    } catch (RuntimeException ex) {
      String failure = mapFailure(ex);
      if ("OUTPUT_SCHEMA_INVALID".equals(failure) || "OUTPUT_POLICY_REJECTED".equals(failure)) {
        job.markInvalid(failure, clock.instant());
      } else {
        job.fail(failure, clock.instant());
      }
      aiJobRepository.save(job);
      try {
        version.restoreDraft(clock.instant());
        versionRepository.save(version);
      } catch (RuntimeException ignored) {
        // leave as-is if already recoverable
      }
      throw ex;
    }
  }

  private void handleProviderFailure(ClaimedAiJob claim, String failureClass) {
    transactionTemplate.executeWithoutResult(
        status -> {
          AiJob job =
              aiJobRepository
                  .findByPublicIdAndTenantId(claim.job().publicId(), claim.job().tenantId())
                  .orElse(null);
          if (job == null || job.status().isTerminal()) {
            return;
          }
          try {
            job.assertLeaseOwnership(claim.leaseOwner(), claim.fencingToken());
          } catch (IllegalStateException ex) {
            return;
          }
          boolean retryable =
              "PROVIDER_TIMEOUT".equals(failureClass)
                  || "PROVIDER_RATE_LIMITED".equals(failureClass)
                  || "PROVIDER_UNAVAILABLE".equals(failureClass);
          if (retryable && job.attemptCount() < aiProperties.getMaximumAttempts()) {
            Instant next =
                clock.instant().plus(aiProperties.getWorkerRetryBaseDelay().multipliedBy(job.attemptCount()));
            job.scheduleRetry(failureClass, next, clock.instant());
          } else {
            job.fail(failureClass, clock.instant());
            versionRepository
                .findByIdAndTenantId(job.botDefinitionVersionId(), job.tenantId())
                .ifPresent(
                    version -> {
                      try {
                        version.restoreDraft(clock.instant());
                        versionRepository.save(version);
                      } catch (RuntimeException ignored) {
                        // already recoverable
                      }
                    });
          }
          aiJobRepository.save(job);
          auditPort.record(
              job.tenantId(),
              AIBOT_WORKER_ACTOR_ID,
              "AIBOT_AI_JOB_FAILED",
              "AIBOT_AI_JOB",
              job.publicId(),
              "{\"failureClass\":\"" + failureClass + "\",\"actorClass\":\"MACHINE_WORKER\"}");
        });
  }

  private void markTerminalFailure(
      UUID tenantId, String jobPublicId, ClaimedAiJob claim, String failureClass) {
    aiJobRepository
        .findByPublicIdAndTenantId(jobPublicId, tenantId)
        .ifPresent(
            job -> {
              try {
                job.assertLeaseOwnership(claim.leaseOwner(), claim.fencingToken());
              } catch (IllegalStateException ex) {
                return;
              }
              if (job.status() != AiJobStatus.LEASED) {
                return;
              }
              job.failClaimed(failureClass, clock.instant());
              aiJobRepository.save(job);
              auditPort.record(
                  job.tenantId(),
                  AIBOT_WORKER_ACTOR_ID,
                  "AIBOT_AI_JOB_FAILED",
                  "AIBOT_AI_JOB",
                  job.publicId(),
                  "{\"failureClass\":\"" + failureClass + "\",\"actorClass\":\"MACHINE_WORKER\"}");
            });
  }

  private String readPendingInput(String requestJson) {
    try {
      JsonNode node =
          objectMapper.readTree(requestJson == null || requestJson.isBlank() ? "{}" : requestJson);
      return node.path("pendingInput").asText("{}");
    } catch (Exception ex) {
      return "{}";
    }
  }

  private String readLocale(String requestJson) {
    try {
      JsonNode node =
          objectMapper.readTree(requestJson == null || requestJson.isBlank() ? "{}" : requestJson);
      String locale = node.path("locale").asText("ru");
      return locale == null || locale.isBlank() ? "ru" : locale;
    } catch (Exception ex) {
      return "ru";
    }
  }

  private static String mapFailure(Throwable ex) {
    String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    if (message.contains("malformed") || message.contains("schema") || message.contains("configuration_")) {
      return "OUTPUT_SCHEMA_INVALID";
    }
    if (message.contains("security_lint") || message.contains("policy")) {
      return "OUTPUT_POLICY_REJECTED";
    }
    if (message.toLowerCase().contains("timeout")) {
      return "PROVIDER_TIMEOUT";
    }
    if (message.contains("429") || message.toLowerCase().contains("rate")) {
      return "PROVIDER_RATE_LIMITED";
    }
    if (message.contains("PROVIDER_DISABLED")) {
      return "PROVIDER_DISABLED";
    }
    return "PROVIDER_UNAVAILABLE";
  }
}
