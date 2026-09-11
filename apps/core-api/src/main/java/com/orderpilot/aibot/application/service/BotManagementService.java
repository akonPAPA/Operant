package com.orderpilot.aibot.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.aibot.api.BotManagementApi;
import com.orderpilot.aibot.api.model.AiJobAcceptedResponse;
import com.orderpilot.aibot.api.model.AiJobStatusResponse;
import com.orderpilot.aibot.api.model.BotDefinitionVersionResponse;
import com.orderpilot.aibot.api.model.BotDraftResponse;
import com.orderpilot.aibot.api.model.CreateBotDraftRequest;
import com.orderpilot.aibot.api.model.GenerateBotDefinitionRequest;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort;
import com.orderpilot.aibot.application.port.out.AibotAuditPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionRepositoryPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionVersionRepositoryPort;
import com.orderpilot.aibot.application.port.out.PublicIdGenerator;
import com.orderpilot.aibot.domain.aijob.AiJob;
import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import com.orderpilot.aibot.domain.botdefinition.BotDefinition;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionConfiguration;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionVersion;
import com.orderpilot.aibot.domain.botdefinition.BotIntentKey;
import com.orderpilot.aibot.domain.exception.BotDefinitionNotFoundException;
import com.orderpilot.aibot.infrastructure.configuration.OperantAiProperties;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BotManagementService implements BotManagementApi {
  private static final String PROMPT_VERSION = "bot-definition-prompt-v1";
  private static final String PROVIDER_POLICY_VERSION = "aibot-provider-policy-v1";

  private final BotDefinitionRepositoryPort botDefinitionRepository;
  private final BotDefinitionVersionRepositoryPort versionRepository;
  private final AiJobRepositoryPort aiJobRepository;
  private final AibotAuditPort auditPort;
  private final PublicIdGenerator publicIdGenerator;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final OperantAiProperties aiProperties;

  public BotManagementService(
      BotDefinitionRepositoryPort botDefinitionRepository,
      BotDefinitionVersionRepositoryPort versionRepository,
      AiJobRepositoryPort aiJobRepository,
      AibotAuditPort auditPort,
      PublicIdGenerator publicIdGenerator,
      ObjectMapper objectMapper,
      Clock clock,
      OperantAiProperties aiProperties) {
    this.botDefinitionRepository = botDefinitionRepository;
    this.versionRepository = versionRepository;
    this.aiJobRepository = aiJobRepository;
    this.auditPort = auditPort;
    this.publicIdGenerator = publicIdGenerator;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.aiProperties = aiProperties;
  }

  @Override
  @Transactional
  public BotDraftResponse createDraft(UUID tenantId, UUID actorId, CreateBotDraftRequest request) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(request, "request");
    BotDefinition bot =
        new BotDefinition(
            publicIdGenerator.next("bot"),
            tenantId,
            request.name(),
            request.description(),
            actorId,
            clock.instant());
    BotDefinition saved = botDefinitionRepository.save(bot);
    BotDefinitionVersion version =
        new BotDefinitionVersion(
            publicIdGenerator.next("botver"),
            tenantId,
            saved.id(),
            1,
            clock.instant());
    BotDefinitionVersion savedVersion = versionRepository.save(version);
    auditPort.record(
        tenantId,
        actorId,
        "AIBOT_DRAFT_CREATED",
        "AIBOT_BOT_DEFINITION",
        saved.publicId(),
        "{\"version\":1}");
    return new BotDraftResponse(
        saved.publicId(),
        savedVersion.versionNumber(),
        savedVersion.state().name(),
        saved.name(),
        saved.description(),
        saved.createdAt());
  }

  @Override
  @Transactional
  public AiJobAcceptedResponse generate(
      UUID tenantId,
      UUID actorId,
      String botPublicId,
      int versionNumber,
      GenerateBotDefinitionRequest request) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(request, "request");
    if (request.desiredBehavior() == null || request.desiredBehavior().isBlank()) {
      throw new IllegalArgumentException("desired_behavior_required");
    }
    if (request.desiredBehavior().length() > 4000) {
      throw new IllegalArgumentException("desired_behavior_too_long");
    }
    List<String> intentKeys = normalizeIntentKeys(request.allowedIntentKeys());
    if (!aiProperties.isJobExecutionEnabled()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "AI_RUNTIME_UNAVAILABLE");
    }
    BotDefinition bot =
        botDefinitionRepository
            .findByPublicIdAndTenantId(botPublicId, tenantId)
            .orElseThrow(() -> new BotDefinitionNotFoundException("bot_not_found"));
    BotDefinitionVersion version =
        versionRepository
            .findByBotDefinitionIdAndVersionNumberAndTenantId(bot.id(), versionNumber, tenantId)
            .orElseThrow(() -> new BotDefinitionNotFoundException("bot_version_not_found"));

    String desired = request.desiredBehavior().trim();
    String fingerprint =
        AiRequestFingerprint.forGeneration(
            tenantId.toString(),
            bot.publicId(),
            versionNumber,
            desired,
            intentKeys,
            PROMPT_VERSION,
            BotDefinitionConfiguration.SCHEMA_V1,
            PROVIDER_POLICY_VERSION);
    String idempotencyKey =
        request.idempotencyKey() == null || request.idempotencyKey().isBlank()
            ? "gen:" + bot.publicId() + ":v" + versionNumber + ":" + fingerprint.substring(0, 16)
            : request.idempotencyKey().trim();
    if (idempotencyKey.length() > 128) {
      throw new IllegalArgumentException("idempotency_key_too_long");
    }

    var existing =
        aiJobRepository.findByTenantIdAndPurposeAndIdempotencyKey(
            tenantId, AiJobPurpose.BOT_DEFINITION_GENERATION, idempotencyKey);
    if (existing.isPresent()) {
      AiJob prior = existing.get();
      if (!fingerprint.equals(prior.requestFingerprint())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
      }
      return new AiJobAcceptedResponse(prior.publicId(), prior.status().name());
    }

    version.markGenerating(clock.instant());
    versionRepository.save(version);

    String minimized =
        objectMapper
            .createObjectNode()
            .put("desiredBehavior", desired)
            .putPOJO("allowedIntentKeys", intentKeys)
            .toString();

    AiJob job =
        new AiJob(
            publicIdGenerator.next("aijob"),
            tenantId,
            AiJobPurpose.BOT_DEFINITION_GENERATION,
            version.id(),
            idempotencyKey,
            BotDefinitionConfiguration.SCHEMA_V1,
            clock.instant());
    String envelope =
        objectMapper.createObjectNode().put("pendingInput", minimized).toString();
    job.attachRequestEnvelope(envelope, fingerprint, "SYNTHETIC");
    AiJob savedJob = aiJobRepository.save(job);
    auditPort.record(
        tenantId,
        actorId,
        "AIBOT_GENERATION_REQUESTED",
        "AIBOT_AI_JOB",
        savedJob.publicId(),
        "{\"botPublicId\":\"" + bot.publicId() + "\",\"version\":" + versionNumber + "}");
    return new AiJobAcceptedResponse(savedJob.publicId(), savedJob.status().name());
  }

  @Override
  @Transactional(readOnly = true)
  public BotDefinitionVersionResponse getVersion(UUID tenantId, String botPublicId, int versionNumber) {
    BotDefinition bot =
        botDefinitionRepository
            .findByPublicIdAndTenantId(botPublicId, tenantId)
            .orElseThrow(() -> new BotDefinitionNotFoundException("bot_not_found"));
    BotDefinitionVersion version =
        versionRepository
            .findByBotDefinitionIdAndVersionNumberAndTenantId(bot.id(), versionNumber, tenantId)
            .orElseThrow(() -> new BotDefinitionNotFoundException("bot_version_not_found"));
    return toVersionResponse(bot, version);
  }

  @Override
  @Transactional(readOnly = true)
  public AiJobStatusResponse getAiJob(UUID tenantId, String jobPublicId) {
    AiJob job =
        aiJobRepository
            .findByPublicIdAndTenantId(jobPublicId, tenantId)
            .orElseThrow(() -> new BotDefinitionNotFoundException("ai_job_not_found"));
    String summary = "none";
    try {
      JsonNode node = objectMapper.readTree(job.resultJson() == null ? "{}" : job.resultJson());
      if (node.has("validationSummary")) {
        summary = node.path("validationSummary").asText("none");
      } else if (job.status().name().contains("READY")) {
        summary = "validated";
      } else if (job.failureClass() != null) {
        summary = "failed";
      }
    } catch (Exception ignored) {
      summary = "unavailable";
    }
    return new AiJobStatusResponse(
        job.publicId(),
        job.purpose().name(),
        job.status().name(),
        job.createdAt(),
        job.completedAt(),
        summary,
        job.failureClass());
  }

  private List<String> normalizeIntentKeys(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      throw new IllegalArgumentException("allowed_intent_keys_required");
    }
    if (raw.size() > 20) {
      throw new IllegalArgumentException("too_many_intent_keys");
    }
    Set<String> seen = new HashSet<>();
    List<String> normalized = new ArrayList<>();
    for (String key : raw) {
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException("blank_intent_key");
      }
      String value = key.trim().toUpperCase(Locale.ROOT);
      if (!BotIntentKey.isKnown(value) || value.equals(BotIntentKey.UNSUPPORTED.name())) {
        throw new IllegalArgumentException("unknown_intent_key");
      }
      if (!seen.add(value)) {
        throw new IllegalArgumentException("duplicate_intent_key");
      }
      normalized.add(value);
    }
    return List.copyOf(normalized);
  }

  private BotDefinitionVersionResponse toVersionResponse(BotDefinition bot, BotDefinitionVersion version) {
    List<BotDefinitionVersionResponse.IntentView> intents =
        version.configuration().intents().stream()
            .map(
                i ->
                    new BotDefinitionVersionResponse.IntentView(
                        i.intentKey(),
                        i.description(),
                        i.actionKey(),
                        i.responsePolicyKey(),
                        i.handoffOnLowConfidence()))
            .toList();
    List<String> policies =
        intents.stream().map(BotDefinitionVersionResponse.IntentView::responsePolicyKey).distinct().toList();
    String provider = "none";
    String model = "none";
    try {
      JsonNode prov = objectMapper.readTree(version.providerProvenanceJson());
      provider = prov.path("provider").asText("none");
      model = prov.path("model").asText("none");
    } catch (Exception ignored) {
      // safe defaults
    }
    List<String> findings = List.of();
    try {
      JsonNode val = objectMapper.readTree(version.validationJson());
      if (val.path("findings").isArray()) {
        List<String> collected = new ArrayList<>();
        val.path("findings").forEach(n -> collected.add(n.asText()));
        findings = List.copyOf(collected);
      }
    } catch (Exception ignored) {
      findings = List.of();
    }
    return new BotDefinitionVersionResponse(
        bot.publicId(),
        version.versionNumber(),
        version.state().name(),
        bot.name(),
        intents,
        policies,
        new BotDefinitionVersionResponse.HandoffPolicyView(
            version.configuration().handoffPolicy().onUnknownIntent(),
            version.configuration().handoffPolicy().onSafetyRisk(),
            version.configuration().handoffPolicy().onExplicitHumanRequest()),
        findings,
        new BotDefinitionVersionResponse.ProvenanceSummary(
            provider, model, version.schemaVersion()),
        version.updatedAt());
  }
}
