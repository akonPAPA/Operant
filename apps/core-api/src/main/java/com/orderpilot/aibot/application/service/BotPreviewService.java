package com.orderpilot.aibot.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orderpilot.aibot.api.BotPreviewApi;
import com.orderpilot.aibot.api.model.AiJobAcceptedResponse;
import com.orderpilot.aibot.api.model.PreviewBotMessageRequest;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort;
import com.orderpilot.aibot.application.port.out.AibotAuditPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionRepositoryPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionVersionRepositoryPort;
import com.orderpilot.aibot.application.port.out.PublicIdGenerator;
import com.orderpilot.aibot.domain.aijob.AiJob;
import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import com.orderpilot.aibot.domain.botdefinition.BotIntentDefinition;
import com.orderpilot.aibot.domain.botruntime.BotRuntimePolicy;
import com.orderpilot.aibot.domain.exception.BotDefinitionNotFoundException;
import com.orderpilot.aibot.domain.exception.BotDefinitionNotPreviewableException;
import com.orderpilot.aibot.domain.exception.BotPreviewInputRejectedException;
import com.orderpilot.aibot.infrastructure.configuration.BotRuntimeProperties;
import com.orderpilot.aibot.infrastructure.configuration.OperantAiProperties;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BotPreviewService implements BotPreviewApi {
  private static final String PROMPT_VERSION = "bot-intent-prompt-v1";
  private static final String PROVIDER_POLICY_VERSION = "aibot-provider-policy-v1";

  private final BotDefinitionRepositoryPort botDefinitionRepository;
  private final BotDefinitionVersionRepositoryPort versionRepository;
  private final AiJobRepositoryPort aiJobRepository;
  private final AibotAuditPort auditPort;
  private final PublicIdGenerator publicIdGenerator;
  private final BotRuntimeProperties runtimeProperties;
  private final OperantAiProperties aiProperties;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public BotPreviewService(
      BotDefinitionRepositoryPort botDefinitionRepository,
      BotDefinitionVersionRepositoryPort versionRepository,
      AiJobRepositoryPort aiJobRepository,
      AibotAuditPort auditPort,
      PublicIdGenerator publicIdGenerator,
      BotRuntimeProperties runtimeProperties,
      OperantAiProperties aiProperties,
      ObjectMapper objectMapper,
      Clock clock) {
    this.botDefinitionRepository = botDefinitionRepository;
    this.versionRepository = versionRepository;
    this.aiJobRepository = aiJobRepository;
    this.auditPort = auditPort;
    this.publicIdGenerator = publicIdGenerator;
    this.runtimeProperties = runtimeProperties;
    this.aiProperties = aiProperties;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  @Transactional
  public AiJobAcceptedResponse preview(
      UUID tenantId,
      UUID actorId,
      String botPublicId,
      int versionNumber,
      PreviewBotMessageRequest request) {
    if (!runtimeProperties.isPreviewEnabled()) {
      throw new BotDefinitionNotPreviewableException("preview_disabled");
    }
    Objects.requireNonNull(tenantId, "tenantId");
    String normalized = normalizeMessage(request == null ? null : request.message());
    String locale =
        request == null || request.locale() == null || request.locale().isBlank()
            ? "ru"
            : request.locale().trim();
    if (detectSafety(normalized)) {
      throw new BotPreviewInputRejectedException("safety_risk_rejected");
    }
    if (!aiProperties.isJobExecutionEnabled()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "AI_RUNTIME_UNAVAILABLE");
    }

    var bot =
        botDefinitionRepository
            .findByPublicIdAndTenantId(botPublicId, tenantId)
            .orElseThrow(() -> new BotDefinitionNotFoundException("bot_not_found"));
    var version =
        versionRepository
            .findByBotDefinitionIdAndVersionNumberAndTenantId(bot.id(), versionNumber, tenantId)
            .orElseThrow(() -> new BotDefinitionNotFoundException("bot_version_not_found"));
    try {
      BotRuntimePolicy.assertPreviewable(version);
    } catch (IllegalStateException ex) {
      throw new BotDefinitionNotPreviewableException(ex.getMessage());
    }

    List<String> approvedIntents =
        version.configuration().intents().stream().map(BotIntentDefinition::intentKey).toList();
    List<String> approvedActions =
        version.configuration().intents().stream().map(BotIntentDefinition::actionKey).distinct().toList();
    String fingerprint =
        AiRequestFingerprint.forPreview(
            tenantId.toString(),
            bot.publicId(),
            versionNumber,
            normalized,
            locale,
            approvedIntents,
            approvedActions,
            PROMPT_VERSION,
            AiOutputValidator.INTENT_SCHEMA_V1,
            PROVIDER_POLICY_VERSION);
    String idempotencyKey =
        "preview:" + bot.publicId() + ":v" + versionNumber + ":" + fingerprint.substring(0, 16);
    AiJob existing =
        aiJobRepository
            .findByTenantIdAndPurposeAndIdempotencyKey(
                tenantId, AiJobPurpose.BOT_INTENT_CLASSIFICATION, idempotencyKey)
            .orElse(null);
    if (existing != null) {
      if (!fingerprint.equals(existing.requestFingerprint())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
      }
      return new AiJobAcceptedResponse(existing.publicId(), existing.status().name());
    }

    AiJob created =
        new AiJob(
            publicIdGenerator.next("aijob"),
            tenantId,
            AiJobPurpose.BOT_INTENT_CLASSIFICATION,
            version.id(),
            idempotencyKey,
            AiOutputValidator.INTENT_SCHEMA_V1,
            clock.instant());
    ObjectNode pending = objectMapper.createObjectNode();
    pending.put("pendingInput", normalized);
    pending.put("locale", locale);
    pending.putPOJO("approvedIntents", approvedIntents);
    pending.putPOJO("approvedActions", approvedActions);
    created.attachRequestEnvelope(pending.toString(), fingerprint, "SYNTHETIC");
    AiJob saved = aiJobRepository.save(created);
    auditPort.record(
        tenantId,
        actorId,
        "AIBOT_PREVIEW_REQUESTED",
        "AIBOT_AI_JOB",
        saved.publicId(),
        "{\"botPublicId\":\"" + bot.publicId() + "\",\"version\":" + versionNumber + "}");
    return new AiJobAcceptedResponse(saved.publicId(), saved.status().name());
  }

  private static String normalizeMessage(String message) {
    if (message == null || message.isBlank()) {
      throw new BotPreviewInputRejectedException("message_required");
    }
    if (message.length() > 8000) {
      throw new BotPreviewInputRejectedException("message_too_long");
    }
    if (message.indexOf('\u0000') >= 0) {
      throw new BotPreviewInputRejectedException("null_byte_rejected");
    }
    if (!StandardCharsets.UTF_8.newEncoder().canEncode(message)) {
      throw new BotPreviewInputRejectedException("invalid_encoding");
    }
    String normalized = Normalizer.normalize(message, Normalizer.Form.NFKC).trim();
    int control = 0;
    for (int i = 0; i < normalized.length(); i++) {
      char c = normalized.charAt(i);
      if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
        control++;
      }
    }
    if (control > 5) {
      throw new BotPreviewInputRejectedException("control_chars_rejected");
    }
    return normalized;
  }

  private static boolean detectSafety(String message) {
    String lower = message.toLowerCase(Locale.ROOT);
    return lower.contains("<script")
        || lower.contains("ignore previous")
        || lower.contains("system prompt")
        || lower.contains("api_key")
        || lower.contains("drop table");
  }
}
