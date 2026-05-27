package com.orderpilot.api.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage3Dtos.*;
import com.orderpilot.application.services.ChannelMessageService;
import com.orderpilot.application.services.WebhookEventService;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.InboundEventLedger;
import java.util.*; import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/intake")
public class IntakeMessageController {
  private final ChannelMessageService service; private final WebhookEventService eventService; private final ObjectMapper objectMapper; public IntakeMessageController(ChannelMessageService service, WebhookEventService eventService, ObjectMapper objectMapper){this.service=service; this.eventService=eventService; this.objectMapper=objectMapper;}
  @PostMapping("/messages") public ChannelMessageResponse create(@RequestBody MessageRequest request){ return toResponse(service.create(request)); }
  @PostMapping("/api-upload") public ChannelMessageResponse apiUpload(@RequestBody ApiUploadRequest request){ return toResponse(service.create(new MessageRequest(request.source() == null || request.source().isBlank() ? "API" : request.source().toUpperCase(), request.externalReference(), request.externalReference(), request.customerHint(), request.customerHint(), null, "INBOUND", "TEXT", request.messageText(), writeJson(request)))); }
  @GetMapping("/messages") public List<ChannelMessageResponse> list(){ return service.list().stream().map(this::toResponse).toList(); }
  @GetMapping("/messages/{id}") public ChannelMessageResponse get(@PathVariable UUID id){ return toResponse(service.get(id)); }
  @GetMapping("/conversations/{conversationId}") public List<ChannelMessageResponse> conversation(@PathVariable String conversationId){ return service.conversation(conversationId).stream().map(this::toResponse).toList(); }
  @GetMapping("/events") public List<InboundEventResponse> events(){ return eventService.listLedger().stream().map(this::toEvent).toList(); }
  @GetMapping("/events/{id}") public InboundEventResponse event(@PathVariable UUID id){ return toEvent(eventService.getLedger(id)); }
  private ChannelMessageResponse toResponse(ChannelMessage m){ return new ChannelMessageResponse(m.getId(), m.getChannel(), m.getExternalMessageId(), m.getConversationId(), m.getSenderHandle(), m.getMessageType(), m.getTextContent(), m.getStatus(), m.getReceivedAt()); }
  private InboundEventResponse toEvent(InboundEventLedger event){ return new InboundEventResponse(event.getId(), event.getSource(), event.getExternalEventId(), event.getEventType(), event.getFingerprintSha256(), event.getStatus(), event.getRawPayloadStorageKey()); }
  private String writeJson(Object value){ try { return objectMapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalArgumentException("Unable to serialize API upload payload"); } }
}
