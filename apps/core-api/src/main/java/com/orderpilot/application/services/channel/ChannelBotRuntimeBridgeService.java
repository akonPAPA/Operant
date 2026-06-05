package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.ChannelBotBridgeDtos.ChannelBotBridgeEventResponse;
import com.orderpilot.api.dto.ChannelBotBridgeDtos.ChannelBotBridgeResultResponse;
import com.orderpilot.api.dto.Stage7Dtos.BotWebhookAckResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.bot.BotFlowPolicyDecision;
import com.orderpilot.application.services.bot.BotRuntimePolicyService;
import com.orderpilot.application.services.bot.BotRuntimeService;
import com.orderpilot.application.services.bot.RuleBasedBotIntentClassifier;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.bot.BotFlow;
import com.orderpilot.domain.bot.BotIntent;
import com.orderpilot.domain.channel.ChannelBotRuntimeConfiguration;
import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelConnectionRepository;
import com.orderpilot.domain.channel.ChannelIdentityResolutionStatus;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.channel.InboundChannelEvent;
import com.orderpilot.domain.channel.InboundChannelEventRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-06A Messenger Chatbot Integration Layer.
 *
 * <p>Bridges the secure, tenant-scoped {@code channel.ChannelConnection} intake path
 * ({@link ChannelEventNormalizationService}) into the existing controlled bot runtime
 * ({@link BotRuntimeService}). This is the single bridge between verified channel intake
 * and controlled bot workflows; it does not introduce a second Telegram runtime or any
 * new connection/message model.
 *
 * <p>Safety invariants preserved:
 * <ul>
 *   <li>Tenant is resolved from {@link TenantContext} + connection ownership, never the request body.</li>
 *   <li>Verification, ACTIVE-status, provider-match, and replay dedup are delegated to the
 *       existing {@link ChannelEventNormalizationService}.</li>
 *   <li>Duplicate provider delivery cannot create duplicate bot conversations/messages/RFQ/handoff:
 *       a re-delivered event short-circuits once it is already linked to a bot conversation.</li>
 *   <li>{@code externalExecution=DISABLED}: no outbound sends, ERP/1C, or connector writes.</li>
 *   <li>No raw bot token, secret reference, or raw provider payload is ever returned or audited.</li>
 * </ul>
 */
@Service
public class ChannelBotRuntimeBridgeService {
  private static final String EXTERNAL_EXECUTION = "DISABLED";

  private final ChannelConnectionRepository connectionRepository;
  private final ChannelEventNormalizationService normalizationService;
  private final InboundChannelEventRepository eventRepository;
  private final BotRuntimeService botRuntimeService;
  private final BotRuntimeConfigurationService configurationService;
  private final BotRuntimePolicyService policyService;
  private final ChannelIdentityResolverService identityResolverService;
  private final RuleBasedBotIntentClassifier intentClassifier;
  private final AuditEventService auditEventService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ChannelBotRuntimeBridgeService(
      ChannelConnectionRepository connectionRepository,
      ChannelEventNormalizationService normalizationService,
      InboundChannelEventRepository eventRepository,
      BotRuntimeService botRuntimeService,
      BotRuntimeConfigurationService configurationService,
      BotRuntimePolicyService policyService,
      ChannelIdentityResolverService identityResolverService,
      RuleBasedBotIntentClassifier intentClassifier,
      AuditEventService auditEventService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.connectionRepository = connectionRepository;
    this.normalizationService = normalizationService;
    this.eventRepository = eventRepository;
    this.botRuntimeService = botRuntimeService;
    this.configurationService = configurationService;
    this.policyService = policyService;
    this.identityResolverService = identityResolverService;
    this.intentClassifier = intentClassifier;
    this.auditEventService = auditEventService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Verify and normalize a provider webhook against the managed connection, then drive the
   * controlled bot runtime for supported providers. Telegram-first for OP-CAP-06A.
   */
  @Transactional
  public ChannelBotBridgeResultResponse handleInbound(UUID connectionId, ChannelProviderType providerType, JsonNode payload, Map<String, String> headers) {
    UUID tenantId = TenantContext.requireTenantId();
    // Delegate trust decisions to the existing secure path:
    // tenant ownership + provider match + ACTIVE status + verification + replay dedup + persistence.
    InboundChannelEvent event = normalizationService.normalize(connectionId, providerType, payload, headers);
    ChannelConnection connection = connectionRepository.findByIdAndTenantId(connectionId, tenantId)
        .orElseThrow(() -> new NotFoundException("Channel connection not found"));

    // Idempotency: a re-delivered provider event is returned by normalize() as the existing row.
    // If it was already bridged, do not re-run the bot runtime.
    if (event.getBotConversationId() != null) {
      audit("BOT_CHANNEL_EVENT_DUPLICATE_IGNORED", event, connection, safe(event.getBotRuntimeStatus()), null);
      return result(event, connection, "DUPLICATE_IGNORED",
          "Duplicate provider event ignored. No new bot workflow, RFQ, draft, or handoff was created.");
    }

    if (providerType != ChannelProviderType.TELEGRAM) {
      event.markBotNotBridged("NOT_BRIDGED_PROVIDER_" + providerType.name(), clock.instant());
      eventRepository.save(event);
      audit("BOT_CHANNEL_EVENT_NOT_BRIDGED", event, connection, "NOT_BRIDGED", null);
      return result(event, connection, "NOT_BRIDGED",
          "Inbound event stored. The controlled bot runtime supports Telegram in OP-CAP-06A; other providers remain intake-only.");
    }

    if (!isProcessableTelegram(payload)) {
      event.markBotBridgeFailed("Malformed Telegram payload for controlled bot runtime", clock.instant());
      eventRepository.save(event);
      audit("BOT_CHANNEL_EVENT_BRIDGE_REJECTED", event, connection, "MALFORMED_PROVIDER_PAYLOAD", null);
      return result(event, connection, "BRIDGE_REJECTED",
          "Inbound event stored but could not be processed by the controlled bot runtime. Routed for operator review.");
    }

    // OP-CAP-06C: deterministically resolve the verified inbound sender to a known customer/contact
    // before policy/runtime. Identity is advisory context only; it never bypasses configuration or
    // runtime validation. A blocked sender never reaches the runtime.
    ChannelIdentityResolution identity = identityResolverService.resolve(providerType, event.getSourceActorExternalId());
    auditIdentity(event, connection, identity);
    if (identity.isBlocked()) {
      event.markBotNotBridged("IDENTITY_BLOCKED", clock.instant());
      eventRepository.save(event);
      return identityBlockedResult(event, connection);
    }

    // OP-CAP-06B: consult the tenant/connection-scoped controlled runtime configuration before
    // driving the runtime. A disabled or policy-blocked flow never reaches the runtime, so no
    // RFQ/draft/price/availability output or bot conversation is produced for it.
    ChannelBotRuntimeConfiguration config = configurationService.getOrCreateDefaultForConnection(connectionId);
    BotIntent intent = intentClassifier.detect(event.getNormalizedText());
    BotFlow flow = BotFlow.fromIntent(intent);
    BotFlowPolicyDecision decision = policyService.decide(config, flow, contextFor(identity));
    if (!decision.allowed()) {
      event.markBotNotBridged("CONFIG_BLOCKED:" + decision.reasonCode().name(), clock.instant());
      eventRepository.save(event);
      auditConfigBlocked(event, connection, decision);
      return configBlockedResult(event, connection, decision);
    }

    BotWebhookAckResponse ack = botRuntimeService.handleTelegramUpdate(payload, verificationModeFor(connection));
    event.linkBotRuntime(ack.conversationId(), ack.messageId(), ack.status(), clock.instant());
    eventRepository.save(event);
    audit("BOT_CHANNEL_EVENT_BRIDGED", event, connection, ack.status(), ack);
    return resultFromAck(event, connection, ack);
  }

  @Transactional(readOnly = true)
  public List<ChannelBotBridgeEventResponse> listBridgedEvents() {
    return normalizationService.list().stream().map(ChannelBotRuntimeBridgeService::toEventResponse).toList();
  }

  private static ChannelBotBridgeEventResponse toEventResponse(InboundChannelEvent e) {
    return new ChannelBotBridgeEventResponse(
        e.getId(),
        e.getChannelConnectionId(),
        e.getProviderType().name(),
        e.getExternalEventId(),
        e.getSourceActorType(),
        e.getSourceActorExternalId(),
        e.getNormalizedText(),
        e.getStatus(),
        e.getVerificationStatus(),
        e.getBotConversationId(),
        e.getBotMessageId(),
        e.getBotRuntimeStatus(),
        e.getReceivedAt(),
        e.getProcessedAt());
  }

  private ChannelBotBridgeResultResponse result(InboundChannelEvent event, ChannelConnection connection, String bridgeStatus, String message) {
    return new ChannelBotBridgeResultResponse(
        event.getId(),
        connection.getId(),
        connection.getProviderType().name(),
        event.getExternalEventId(),
        event.getStatus(),
        bridgeStatus,
        event.getBotConversationId(),
        event.getBotMessageId(),
        null,
        false,
        null,
        message,
        event.getReceivedAt(),
        event.getVerificationStatus(),
        EXTERNAL_EXECUTION);
  }

  private ChannelBotBridgeResultResponse resultFromAck(InboundChannelEvent event, ChannelConnection connection, BotWebhookAckResponse ack) {
    return new ChannelBotBridgeResultResponse(
        event.getId(),
        connection.getId(),
        connection.getProviderType().name(),
        event.getExternalEventId(),
        event.getStatus(),
        ack.status(),
        ack.conversationId(),
        ack.messageId(),
        ack.intent() == null ? null : ack.intent().name(),
        ack.requiresHumanReview(),
        ack.createdRfqDraftId(),
        ack.responseMessage(),
        event.getReceivedAt(),
        event.getVerificationStatus(),
        EXTERNAL_EXECUTION);
  }

  private ChannelBotBridgeResultResponse configBlockedResult(InboundChannelEvent event, ChannelConnection connection, BotFlowPolicyDecision decision) {
    String message = decision.warningMessage() == null || decision.warningMessage().isBlank()
        ? "This bot flow is not enabled for this connection. Routed to operator review."
        : decision.warningMessage();
    return new ChannelBotBridgeResultResponse(
        event.getId(),
        connection.getId(),
        connection.getProviderType().name(),
        event.getExternalEventId(),
        event.getStatus(),
        "BLOCKED_BY_CONFIG",
        null,
        null,
        decision.flow().name(),
        decision.requiresHandoff(),
        null,
        message,
        event.getReceivedAt(),
        event.getVerificationStatus(),
        EXTERNAL_EXECUTION);
  }

  private BotRuntimePolicyService.Context contextFor(ChannelIdentityResolution identity) {
    return switch (identity.status()) {
      case RESOLVED -> BotRuntimePolicyService.Context.identified();
      case AMBIGUOUS -> BotRuntimePolicyService.Context.ambiguous();
      case BLOCKED -> BotRuntimePolicyService.Context.blocked();
      case UNKNOWN, NOT_APPLICABLE -> BotRuntimePolicyService.Context.unidentified();
    };
  }

  private ChannelBotBridgeResultResponse identityBlockedResult(InboundChannelEvent event, ChannelConnection connection) {
    return new ChannelBotBridgeResultResponse(
        event.getId(),
        connection.getId(),
        connection.getProviderType().name(),
        event.getExternalEventId(),
        event.getStatus(),
        "IDENTITY_BLOCKED",
        null,
        null,
        null,
        true,
        null,
        "This sender is blocked for this tenant. No bot business response was produced; routed for operator review.",
        event.getReceivedAt(),
        event.getVerificationStatus(),
        EXTERNAL_EXECUTION);
  }

  /** Audit identity resolution with safe identifiers only (no raw sender phone/name, no secrets). */
  private void auditIdentity(InboundChannelEvent event, ChannelConnection connection, ChannelIdentityResolution identity) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("channelConnectionId", str(connection.getId()));
    metadata.put("providerType", connection.getProviderType().name());
    metadata.put("inboundChannelEventId", str(event.getId()));
    metadata.put("identityStatus", identity.status().name());
    metadata.put("channelIdentityId", str(identity.channelIdentityId()));
    metadata.put("customerAccountId", str(identity.customerAccountId()));
    metadata.put("customerContactId", str(identity.customerContactId()));
    metadata.put("reason", safe(identity.reason()));
    metadata.put("externalExecution", EXTERNAL_EXECUTION);
    String action = identity.isBlocked() ? "BOT_CHANNEL_EVENT_IDENTITY_BLOCKED" : "BOT_CHANNEL_EVENT_IDENTITY_RESOLVED";
    auditEventService.record(action, "INBOUND_CHANNEL_EVENT", event.getId().toString(), null, writeJson(metadata));
  }

  /** Audit a configuration-blocked flow with safe metadata only. */
  private void auditConfigBlocked(InboundChannelEvent event, ChannelConnection connection, BotFlowPolicyDecision decision) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("channelConnectionId", str(connection.getId()));
    metadata.put("providerType", connection.getProviderType().name());
    metadata.put("inboundChannelEventId", str(event.getId()));
    metadata.put("externalEventId", safe(event.getExternalEventId()));
    metadata.put("flow", decision.flow().name());
    metadata.put("policyReason", decision.reasonCode().name());
    metadata.put("requiresHumanReview", decision.requiresHandoff());
    metadata.put("configId", str(decision.configId()));
    metadata.put("bridgeStatus", "BLOCKED_BY_CONFIG");
    metadata.put("externalExecution", EXTERNAL_EXECUTION);
    auditEventService.record("BOT_CHANNEL_EVENT_BLOCKED_BY_CONFIG", "INBOUND_CHANNEL_EVENT", event.getId().toString(), null, writeJson(metadata));
  }

  private WebhookVerificationMode verificationModeFor(ChannelConnection connection) {
    String mode = connection.getWebhookVerificationMode();
    if (mode == null || mode.isBlank()) {
      return WebhookVerificationMode.DISABLED_FOR_LOCAL_DEV;
    }
    try {
      return WebhookVerificationMode.valueOf(mode);
    } catch (IllegalArgumentException ex) {
      return WebhookVerificationMode.DISABLED_FOR_LOCAL_DEV;
    }
  }

  private boolean isProcessableTelegram(JsonNode update) {
    if (update == null) {
      return false;
    }
    JsonNode message = update.path("message");
    JsonNode chat = message.path("chat");
    JsonNode text = message.path("text");
    return !message.isMissingNode()
        && !chat.path("id").isMissingNode()
        && !message.path("message_id").isMissingNode()
        && !text.isMissingNode()
        && !text.asText("").isBlank();
  }

  /** Audit with safe metadata only. Never includes secret references, raw tokens, or raw payloads. */
  private void audit(String type, InboundChannelEvent event, ChannelConnection connection, String bridgeStatus, BotWebhookAckResponse ack) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("channelConnectionId", str(connection.getId()));
    metadata.put("providerType", connection.getProviderType().name());
    metadata.put("inboundChannelEventId", str(event.getId()));
    metadata.put("externalEventId", safe(event.getExternalEventId()));
    metadata.put("botConversationId", ack == null ? str(event.getBotConversationId()) : str(ack.conversationId()));
    metadata.put("botMessageId", ack == null ? str(event.getBotMessageId()) : str(ack.messageId()));
    metadata.put("detectedIntent", ack == null || ack.intent() == null ? "" : ack.intent().name());
    metadata.put("bridgeStatus", safe(bridgeStatus));
    metadata.put("requiresHumanReview", ack != null && ack.requiresHumanReview());
    metadata.put("externalExecution", EXTERNAL_EXECUTION);
    auditEventService.record(type, "INBOUND_CHANNEL_EVENT", event.getId().toString(), null, writeJson(metadata));
  }

  private String writeJson(Map<String, Object> metadata) {
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (Exception ex) {
      return "{\"externalExecution\":\"" + EXTERNAL_EXECUTION + "\"}";
    }
  }

  private static String str(UUID value) {
    return value == null ? "" : value.toString();
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
