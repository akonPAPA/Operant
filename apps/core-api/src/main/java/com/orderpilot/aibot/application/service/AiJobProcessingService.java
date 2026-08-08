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
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiJobProcessingService {
  private static final Logger log = LoggerFactory.getLogger(AiJobProcessingService.class);

  /** Explicit machine actor for background AI job audit; never null. */
  static final UUID AIBOT_WORKER_ACTOR_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

  /** Typed result of completing a claimed job's provider output within one transaction. */
  enum ProcessingOutcome {
    SUGGESTION_READY,
    TERMINAL_VALIDATION_FAILURE
  }

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

    ProcessingOutcome outcome =
        transactionTemplate.execute(status -> completeWithProviderResult(claim, providerResult));
    if (outcome == ProcessingOutcome.TERMINAL_VALIDATION_FAILURE) {
      // Terminal validation failure is a COMMITTED business outcome, not a transaction
      // fault: surface it for monitoring after commit — never as a rollback-triggering throw.
      log.info("aibot_output_validation_rejected jobPublicId={}", running.publicId());
    }
  }

  private ProcessingOutcome completeWithProviderResult(
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
    String outputHash = AiRequestFingerprint.sha256Hex(providerResult.normalizedResponseText());
    job.markOutputReceived(outputHash, clock.instant());

    // Validate provider output in isolation. A validation verdict (malformed JSON,
    // unknown schema, forbidden intent/field, oversized output, out-of-range confidence,
    // …) is raised by the validator as IllegalArgumentException and is a NORMAL terminal
    // business outcome that MUST commit. Every other failure (persistence, optimistic
    // lock, programming, transport) is NOT caught here, so it propagates and rolls the
    // whole authoritative transaction back — no partial terminal state is committed.
    ValidatedOutput validated;
    try {
      validated = validateProviderOutput(job.purpose(), providerResult.normalizedResponseText(), version);
    } catch (IllegalArgumentException validationFailure) {
      return terminalValidationFailure(tenantId, job, version, validationFailure);
    }

    // Accepted advisory result. Persistence failures below intentionally roll back.
    if (job.purpose() == AiJobPurpose.BOT_DEFINITION_GENERATION) {
      BotDefinitionConfiguration configuration = validated.configuration();
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
      AiOutputValidator.IntentClassification classification = validated.classification();
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
    return ProcessingOutcome.SUGGESTION_READY;
  }

  /**
   * Parses/validates provider output for the job purpose. Throws {@link IllegalArgumentException}
   * — and only that — on a validation verdict; never persists.
   */
  private ValidatedOutput validateProviderOutput(
      AiJobPurpose purpose, String normalizedResponseText, BotDefinitionVersion version) {
    if (purpose == AiJobPurpose.BOT_DEFINITION_GENERATION) {
      return new ValidatedOutput(outputValidator.parseBotDefinition(normalizedResponseText), null);
    }
    AiOutputValidator.IntentClassification classification =
        outputValidator.parseIntentClassification(normalizedResponseText);
    // Capability containment: the classified intent must be one THIS exact bot version was
    // configured (and approved) to handle. A globally-known intent the bot does not declare would
    // silently expand the bot's capability beyond its BotDefinitionVersion, so reject it as a
    // policy verdict (commits terminal INVALID, never a raw provider passthrough).
    if (version.findIntent(classification.intentKey()).isEmpty()) {
      throw new IllegalArgumentException("intent_forbidden_by_bot_capability");
    }
    return new ValidatedOutput(null, classification);
  }

  /**
   * Commits the terminal INVALID transition for a validation failure. Runs inside the
   * caller's authoritative transaction and RETURNS normally (no throw) so the terminal job
   * state, the draft restore, and the audit record all commit together. Throwing here would
   * roll the transaction back and strand the durable job in RUNNING — the defect this method
   * exists to prevent. Persistence failures inside this method are NOT swallowed (except the
   * documented already-recoverable draft restore) and will still roll the transaction back.
   */
  private ProcessingOutcome terminalValidationFailure(
      UUID tenantId,
      AiJob job,
      BotDefinitionVersion version,
      IllegalArgumentException validationFailure) {
    String failure = classifyValidationFailure(validationFailure);
    job.markInvalid(failure, clock.instant());
    aiJobRepository.save(job);
    try {
      version.restoreDraft(clock.instant());
      versionRepository.save(version);
    } catch (IllegalStateException alreadyRecoverable) {
      // Version already in a recoverable/draft state — the INVALID job outcome stands.
    }
    auditPort.record(
        tenantId,
        AIBOT_WORKER_ACTOR_ID,
        "AIBOT_AI_JOB_INVALID",
        "AIBOT_AI_JOB",
        job.publicId(),
        "{\"status\":\"INVALID\",\"failureClass\":\"" + failure + "\",\"actorClass\":\"MACHINE_WORKER\"}");
    return ProcessingOutcome.TERMINAL_VALIDATION_FAILURE;
  }

  /** Maps a validation exception to one of the two terminal INVALID failure codes. */
  private static String classifyValidationFailure(IllegalArgumentException ex) {
    String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
    if (message.contains("policy")
        || message.contains("security_lint")
        || message.contains("authority")
        || message.contains("forbidden")) {
      return "OUTPUT_POLICY_REJECTED";
    }
    return "OUTPUT_SCHEMA_INVALID";
  }

  private record ValidatedOutput(
      BotDefinitionConfiguration configuration,
      AiOutputValidator.IntentClassification classification) {}

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
