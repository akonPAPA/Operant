package com.orderpilot.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.orderpilot.api.dto.Stage12Dtos.InboundChannelEventResponse;
import com.orderpilot.application.services.channel.ChannelEventNormalizationService;
import com.orderpilot.domain.channel.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks/channels")
public class ChannelWebhookController {
  private final ChannelEventNormalizationService normalizationService;
  public ChannelWebhookController(ChannelEventNormalizationService normalizationService) { this.normalizationService = normalizationService; }

  @PostMapping("/telegram/{connectionId}") public InboundChannelEventResponse telegram(@PathVariable UUID connectionId, @RequestHeader Map<String, String> headers, @RequestBody JsonNode payload) { return toResponse(normalizationService.normalize(connectionId, ChannelProviderType.TELEGRAM, payload, headers)); }
  @PostMapping("/whatsapp/{connectionId}") public InboundChannelEventResponse whatsapp(@PathVariable UUID connectionId, @RequestHeader Map<String, String> headers, @RequestBody JsonNode payload) { return toResponse(normalizationService.normalize(connectionId, ChannelProviderType.WHATSAPP, payload, headers)); }
  // OP-CAP-42J: Meta Path-2 must verify X-Hub-Signature-256 against the byte-exact wire body, so the
  // raw JSON body is received as a String (not a re-serialized JsonNode) and parsed only after the
  // verifier accepts it. The raw body is passed straight to the service and is never logged.
  @PostMapping("/meta-messenger/{connectionId}") public InboundChannelEventResponse metaMessenger(@PathVariable UUID connectionId, @RequestHeader Map<String, String> headers, @RequestBody String rawBody) { return toResponse(normalizationService.normalize(connectionId, ChannelProviderType.META_MESSENGER, rawBody, headers)); }
  @PostMapping("/viber/{connectionId}") public InboundChannelEventResponse viber(@PathVariable UUID connectionId, @RequestHeader Map<String, String> headers, @RequestBody JsonNode payload) { return toResponse(normalizationService.normalize(connectionId, ChannelProviderType.VIBER, payload, headers)); }
  @PostMapping("/wechat/{connectionId}") public InboundChannelEventResponse wechat(@PathVariable UUID connectionId, @RequestHeader Map<String, String> headers, @RequestBody JsonNode payload) { return toResponse(normalizationService.normalize(connectionId, ChannelProviderType.WECHAT, payload, headers)); }

  private static InboundChannelEventResponse toResponse(InboundChannelEvent e) {
    return new InboundChannelEventResponse(e.getId(), e.getChannelConnectionId(), e.getProviderType().name(), e.getSourceActorType(), e.getNormalizedText(), e.getStatus(), e.getVerificationStatus(), e.getVerificationReason(), e.getReceivedAt(), e.getProcessedAt(), e.getErrorCode());
  }
}
