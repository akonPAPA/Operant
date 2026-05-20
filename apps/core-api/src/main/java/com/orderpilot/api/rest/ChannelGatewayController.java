package com.orderpilot.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
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

  public ChannelGatewayController(ChannelGatewayService gatewayService, WhatsAppInboundAdapter whatsAppInboundAdapter, WhatsAppSignatureVerifier whatsAppSignatureVerifier) {
    this.gatewayService = gatewayService;
    this.whatsAppInboundAdapter = whatsAppInboundAdapter;
    this.whatsAppSignatureVerifier = whatsAppSignatureVerifier;
  }

  @PostMapping("/messages")
  public ChannelGatewayMessageResponse create(@RequestBody ChannelGatewayMessageRequest request) {
    ChannelType channelType = ChannelType.valueOf(request.channelType());
    ChannelMessage message = gatewayService.accept(new NormalizedInboundMessage(null, channelType, request.externalMessageId(), request.externalConversationId(), request.externalSenderId(), request.senderDisplayName(), request.senderPhone(), request.rawText(), request.attachmentRefs(), null, request.rawPayloadJson(), request.idempotencyKey()));
    return toMessage(message);
  }

  @PostMapping("/whatsapp/webhook")
  public ChannelGatewayAckResponse whatsappWebhook(@RequestHeader Map<String, String> headers, @RequestBody JsonNode payload) {
    WebhookSignatureVerificationResult verification = whatsAppSignatureVerifier.verify(headers, payload == null ? "" : payload.toString(), ChannelType.WHATSAPP, TenantContext.requireTenantId());
    if (!verification.accepted()) {
      return new ChannelGatewayAckResponse("REJECTED_SIGNATURE_VERIFICATION_FAILED", 0, false, verification.mode().name(), List.of());
    }
    List<ChannelMessage> accepted = whatsAppInboundAdapter.normalize(payload).stream()
        .map(message -> gatewayService.accept(message, verification.mode()))
        .toList();
    String status = accepted.isEmpty() ? "IGNORED_NO_SUPPORTED_MESSAGES" : "ACCEPTED_INBOUND_ONLY";
    return new ChannelGatewayAckResponse(status, accepted.size(), verification.mode() == WebhookVerificationMode.CONFIGURED_VERIFY_ONLY, verification.mode().name(), accepted.stream().map(this::toMessage).toList());
  }

  private ChannelGatewayMessageResponse toMessage(ChannelMessage message) {
    return new ChannelGatewayMessageResponse(message.getId(), message.getChannel(), message.getExternalMessageId(), message.getConversationId(), message.getSenderHandle(), message.getMessageType(), message.getTextContent(), message.getStatus(), message.getChannelIdentityId(), message.getCustomerAccountId(), message.getCustomerContactId(), message.getSignatureVerificationMode(), message.getReceivedAt());
  }
}
