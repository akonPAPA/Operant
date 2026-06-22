package com.orderpilot.api.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage10DOmnichannelDtos.*;
import com.orderpilot.application.services.channel.*;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ChannelMessage;
import java.util.Map;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/channel-gateway")
public class ChannelGatewayController {
  private final ChannelGatewayService gatewayService;
  private final WhatsAppInboundAdapter whatsAppInboundAdapter;
  private final WhatsAppSignatureVerifier whatsAppSignatureVerifier;
  private final ObjectMapper objectMapper;

  public ChannelGatewayController(ChannelGatewayService gatewayService, WhatsAppInboundAdapter whatsAppInboundAdapter, WhatsAppSignatureVerifier whatsAppSignatureVerifier, ObjectMapper objectMapper) {
    this.gatewayService = gatewayService;
    this.whatsAppInboundAdapter = whatsAppInboundAdapter;
    this.whatsAppSignatureVerifier = whatsAppSignatureVerifier;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/messages")
  public ChannelGatewayMessageResponse create(@RequestBody ChannelGatewayMessageRequest request) {
    ChannelType channelType = ChannelType.valueOf(request.channelType());
    ChannelMessage message = gatewayService.accept(new NormalizedInboundMessage(null, channelType, request.externalMessageId(), request.externalConversationId(), request.externalSenderId(), request.senderDisplayName(), request.senderPhone(), request.rawText(), request.attachmentRefs(), null, request.rawPayloadJson(), request.idempotencyKey()));
    return toMessage(message);
  }

  // OP-CAP-42H: the Meta webhook signature is computed by the provider over the EXACT raw request body
  // bytes. The controller therefore receives the raw body as a String and verifies the signature against
  // those exact bytes BEFORE any JSON parse/reserialization. Parsing the body into a JsonNode and signing
  // its re-serialized form (the prior `payload.toString()` behaviour) could change whitespace/key-order/
  // escaping and break a legitimate signature (or, conversely, mask a byte-level mismatch). JSON parsing
  // happens only after the signature has been accepted.
  @PostMapping("/whatsapp/webhook")
  public ChannelGatewayAckResponse whatsappWebhook(@RequestHeader Map<String, String> headers, @RequestBody(required = false) String rawBody) {
    String body = rawBody == null ? "" : rawBody;
    WebhookSignatureVerificationResult verification = whatsAppSignatureVerifier.verify(headers, body, ChannelType.WHATSAPP, TenantContext.requireTenantId());
    if (!verification.accepted()) {
      return new ChannelGatewayAckResponse("REJECTED_SIGNATURE_VERIFICATION_FAILED", 0, false, verification.mode().name(), List.of());
    }
    JsonNode payload = parsePayload(body);
    List<ChannelMessage> accepted = whatsAppInboundAdapter.normalize(payload).stream()
        .map(message -> gatewayService.accept(message, verification.mode()))
        .toList();
    String status = accepted.isEmpty() ? "IGNORED_NO_SUPPORTED_MESSAGES" : "ACCEPTED_INBOUND_ONLY";
    return new ChannelGatewayAckResponse(status, accepted.size(), verification.mode() == WebhookVerificationMode.CONFIGURED_VERIFY_ONLY, verification.mode().name(), accepted.stream().map(this::toMessage).toList());
  }

  /**
   * Parses the raw webhook body into a JsonNode only after signature verification has succeeded. A
   * structurally-invalid body fails closed with the same stable, redacted error contract the framework
   * produces for a malformed {@code @RequestBody} ({@code 400 BAD_REQUEST}, "Request body is not valid
   * JSON" via {@code GlobalExceptionHandler}) — no Jackson parser internals or raw body echo.
   */
  private JsonNode parsePayload(String body) {
    try {
      return objectMapper.readTree(body);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Request body is not valid JSON");
    }
  }

  private ChannelGatewayMessageResponse toMessage(ChannelMessage message) {
    return new ChannelGatewayMessageResponse(message.getId(), message.getChannel(), message.getExternalMessageId(), message.getConversationId(), message.getSenderHandle(), message.getMessageType(), message.getTextContent(), message.getStatus(), message.getChannelIdentityId(), message.getCustomerAccountId(), message.getCustomerContactId(), message.getSignatureVerificationMode(), message.getReceivedAt());
  }
}
