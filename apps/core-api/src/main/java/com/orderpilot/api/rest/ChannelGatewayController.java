package com.orderpilot.api.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage10DOmnichannelDtos.*;
import com.orderpilot.application.services.LegacyWebhookIngressGuard;
import com.orderpilot.application.services.channel.*;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.intake.ChannelMessage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/channel-gateway")
public class ChannelGatewayController {
  private final ChannelGatewayService gatewayService;
  private final WhatsAppInboundAdapter whatsAppInboundAdapter;
  private final WhatsAppSignatureVerifier whatsAppSignatureVerifier;
  private final WebhookIntakeConnectionResolver connectionResolver;
  private final LegacyWebhookIngressGuard legacyIngressGuard;
  private final ObjectMapper objectMapper;

  public ChannelGatewayController(
      ChannelGatewayService gatewayService,
      WhatsAppInboundAdapter whatsAppInboundAdapter,
      WhatsAppSignatureVerifier whatsAppSignatureVerifier,
      WebhookIntakeConnectionResolver connectionResolver,
      LegacyWebhookIngressGuard legacyIngressGuard,
      ObjectMapper objectMapper) {
    this.gatewayService = gatewayService;
    this.whatsAppInboundAdapter = whatsAppInboundAdapter;
    this.whatsAppSignatureVerifier = whatsAppSignatureVerifier;
    this.connectionResolver = connectionResolver;
    this.legacyIngressGuard = legacyIngressGuard;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/messages")
  public ChannelGatewayMessageResponse create(@RequestBody ChannelGatewayMessageRequest request) {
    ChannelType channelType = ChannelType.valueOf(request.channelType());
    ChannelMessage message =
        gatewayService.accept(
            new NormalizedInboundMessage(
                null,
                channelType,
                request.externalMessageId(),
                request.externalConversationId(),
                request.externalSenderId(),
                request.senderDisplayName(),
                request.senderPhone(),
                request.rawText(),
                request.attachmentRefs(),
                null,
                request.rawPayloadJson(),
                request.idempotencyKey()));
    return toMessage(message);
  }

  /**
   * Legacy unqualified WhatsApp webhook. Disabled outside local/test — production provider ingress
   * must use the connection-scoped route.
   */
  @PostMapping("/whatsapp/webhook")
  public ChannelGatewayAckResponse legacyUnqualifiedWhatsappWebhook() {
    legacyIngressGuard.requireLocalOrTest();
    throw new WebhookAuthenticationException();
  }

  /**
   * Connection-scoped WhatsApp webhook. Resolves tenant from the server-owned connection after
   * ACTIVE/provider checks, then verifies Meta {@code X-Hub-Signature-256} over the exact raw body.
   */
  @PostMapping("/whatsapp/webhook/{connectionId}")
  public ChannelGatewayAckResponse whatsappWebhook(
      @PathVariable UUID connectionId,
      @RequestHeader Map<String, String> headers,
      @RequestBody(required = false) String rawBody) {
    ChannelConnection connection =
        connectionResolver.resolveActiveConnection(connectionId, ChannelProviderType.WHATSAPP);
    TenantContext.setTenantId(connection.getTenantId());
    String body = rawBody == null ? "" : rawBody;
    WebhookSignatureVerificationResult verification =
        whatsAppSignatureVerifier.verify(headers, body, ChannelType.WHATSAPP, connection.getTenantId());
    if (!verification.accepted()) {
      throw new WebhookAuthenticationException();
    }
    JsonNode payload = parsePayload(body);
    List<ChannelMessage> accepted =
        whatsAppInboundAdapter.normalize(payload).stream()
            .map(message -> gatewayService.accept(message, verification.mode()))
            .toList();
    String status = accepted.isEmpty() ? "IGNORED_NO_SUPPORTED_MESSAGES" : "ACCEPTED_INBOUND_ONLY";
    return new ChannelGatewayAckResponse(
        status,
        accepted.size(),
        verification.mode() == WebhookVerificationMode.CONFIGURED_VERIFY_ONLY,
        verification.mode().name(),
        accepted.stream().map(this::toMessage).toList());
  }

  private JsonNode parsePayload(String body) {
    try {
      return objectMapper.readTree(body);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Request body is not valid JSON");
    }
  }

  private ChannelGatewayMessageResponse toMessage(ChannelMessage message) {
    return new ChannelGatewayMessageResponse(
        message.getId(),
        message.getChannel(),
        message.getExternalMessageId(),
        message.getConversationId(),
        message.getSenderHandle(),
        message.getMessageType(),
        message.getTextContent(),
        message.getStatus(),
        message.getChannelIdentityId(),
        message.getCustomerAccountId(),
        message.getCustomerContactId(),
        message.getSignatureVerificationMode(),
        message.getReceivedAt());
  }
}
