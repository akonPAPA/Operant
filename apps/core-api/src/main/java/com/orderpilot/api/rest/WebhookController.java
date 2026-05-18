package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage3Dtos.*;
import com.orderpilot.application.services.*;
import com.orderpilot.domain.intake.WebhookEvent;
import java.util.*; import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/webhooks")
public class WebhookController {
  private final WebhookEventService eventService; private final ChannelMessageService messageService;
  public WebhookController(WebhookEventService eventService, ChannelMessageService messageService){this.eventService=eventService; this.messageService=messageService;}
  @PostMapping("/email") public ChannelMessageResponse email(@RequestBody EmailWebhookRequest request){ eventService.record("EMAIL", request.externalMessageId(), request.rawPayload(), "{}", false); return toMessage(messageService.create(new MessageRequest("EMAIL", request.externalMessageId(), request.externalMessageId(), request.sender(), request.sender(), null, "INBOUND", "TEXT", request.bodyText(), request.rawPayload()))); }
  @PostMapping("/telegram/{tenantKey}") public ChannelMessageResponse telegram(@PathVariable String tenantKey, @RequestBody WebhookPayloadRequest request){ eventService.record("TELEGRAM", request.externalEventId(), request.rawPayload(), "{\"tenantKey\":\"placeholder\"}", false); return toMessage(messageService.create(new MessageRequest("TELEGRAM", request.externalEventId(), request.externalEventId(), "telegram", "Telegram User", null, "INBOUND", "TEXT", request.rawPayload(), request.rawPayload()))); }
  @PostMapping("/whatsapp/{tenantKey}") public WebhookEventResponse whatsapp(@PathVariable String tenantKey, @RequestBody WebhookPayloadRequest request){ return toEvent(eventService.record("WHATSAPP", request.externalEventId(), request.rawPayload(), "{\"tenantKey\":\"placeholder\"}", false)); }
  @GetMapping("/events") public List<WebhookEventResponse> events(){ return eventService.list().stream().map(this::toEvent).toList(); }
  @GetMapping("/events/{id}") public WebhookEventResponse event(@PathVariable UUID id){ return toEvent(eventService.get(id)); }
  private ChannelMessageResponse toMessage(com.orderpilot.domain.intake.ChannelMessage m){ return new ChannelMessageResponse(m.getId(), m.getChannel(), m.getExternalMessageId(), m.getConversationId(), m.getSenderHandle(), m.getMessageType(), m.getTextContent(), m.getStatus(), m.getReceivedAt()); }
  private WebhookEventResponse toEvent(WebhookEvent e){ return new WebhookEventResponse(e.getId(), e.getProvider(), e.getExternalEventId(), e.isSignatureVerified(), e.isReplayDetected(), e.getStatus(), e.getReceivedAt()); }
}