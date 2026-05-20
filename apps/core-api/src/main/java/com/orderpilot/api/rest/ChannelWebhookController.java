package com.orderpilot.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.orderpilot.api.dto.Stage12Dtos.InboundChannelEventResponse;
import com.orderpilot.application.services.channel.ChannelEventNormalizationService;
import com.orderpilot.domain.channel.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks/channels")
public class ChannelWebhookController {
  private final ChannelEventNormalizationService normalizationService;
  public ChannelWebhookController(ChannelEventNormalizationService normalizationService) { this.normalizationService = normalizationService; }

  @PostMapping("/telegram/{connectionId}") public InboundChannelEventResponse telegram(@PathVariable UUID connectionId, @RequestBody JsonNode payload) { return toResponse(normalizationService.normalize(connectionId, ChannelProviderType.TELEGRAM, payload)); }
  @PostMapping("/whatsapp/{connectionId}") public InboundChannelEventResponse whatsapp(@PathVariable UUID connectionId, @RequestBody JsonNode payload) { return toResponse(normalizationService.normalize(connectionId, ChannelProviderType.WHATSAPP, payload)); }
  @PostMapping("/meta-messenger/{connectionId}") public InboundChannelEventResponse metaMessenger(@PathVariable UUID connectionId, @RequestBody JsonNode payload) { return toResponse(normalizationService.normalize(connectionId, ChannelProviderType.META_MESSENGER, payload)); }
  @PostMapping("/viber/{connectionId}") public InboundChannelEventResponse viber(@PathVariable UUID connectionId, @RequestBody JsonNode payload) { return toResponse(normalizationService.normalize(connectionId, ChannelProviderType.VIBER, payload)); }
  @PostMapping("/wechat/{connectionId}") public InboundChannelEventResponse wechat(@PathVariable UUID connectionId, @RequestBody JsonNode payload) { return toResponse(normalizationService.normalize(connectionId, ChannelProviderType.WECHAT, payload)); }

  private static InboundChannelEventResponse toResponse(InboundChannelEvent e) {
    return new InboundChannelEventResponse(e.getId(), e.getChannelConnectionId(), e.getProviderType().name(), e.getExternalEventId(), e.getSourceActorType(), e.getSourceActorExternalId(), e.getNormalizedText(), e.getPayloadHash(), e.getStatus(), e.getReceivedAt(), e.getProcessedAt(), e.getErrorCode(), e.getErrorMessage());
  }
}
