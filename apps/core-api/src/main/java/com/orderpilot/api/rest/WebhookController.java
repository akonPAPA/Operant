package com.orderpilot.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage3Dtos.AttachmentMetadataRequest;
import com.orderpilot.api.dto.Stage3Dtos.ChannelMessageResponse;
import com.orderpilot.api.dto.Stage3Dtos.EmailWebhookRequest;
import com.orderpilot.api.dto.Stage3Dtos.MessageRequest;
import com.orderpilot.api.dto.Stage3Dtos.WebhookEventResponse;
import com.orderpilot.api.dto.Stage3Dtos.WebhookPayloadRequest;
import com.orderpilot.application.services.ChannelMessageService;
import com.orderpilot.application.services.WebhookEventService;
import com.orderpilot.application.services.WebhookVerificationService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.InboundAttachment;
import com.orderpilot.domain.intake.InboundAttachmentRepository;
import com.orderpilot.domain.intake.WebhookEvent;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {
  private final WebhookEventService eventService;
  private final ChannelMessageService messageService;
  private final WebhookVerificationService verificationService;
  private final InboundAttachmentRepository attachmentRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public WebhookController(WebhookEventService eventService, ChannelMessageService messageService, WebhookVerificationService verificationService, InboundAttachmentRepository attachmentRepository, ObjectMapper objectMapper, Clock clock) {
    this.eventService = eventService;
    this.messageService = messageService;
    this.verificationService = verificationService;
    this.attachmentRepository = attachmentRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @PostMapping("/email")
  public ChannelMessageResponse email(@RequestHeader Map<String, String> headers, @RequestBody EmailWebhookRequest request) {
    String rawPayload = request.rawPayload() == null || request.rawPayload().isBlank() ? writeJson(request) : request.rawPayload();
    var verification = verificationService.verify("EMAIL", rawPayload, headers);
    requireAccepted(verification);
    eventService.record("EMAIL", request.externalMessageId(), rawPayload, writeJson(headers), verification.accepted());
    ChannelMessage message = messageService.create(new MessageRequest("EMAIL", request.externalMessageId(), request.externalMessageId(), request.sender(), request.sender(), null, "INBOUND", "TEXT", request.bodyText(), rawPayload));
    saveAttachmentMetadata(message, request.attachments());
    return toMessage(message);
  }

  @PostMapping("/telegram")
  public ChannelMessageResponse telegram(@RequestHeader Map<String, String> headers, @RequestBody JsonNode payload) {
    return telegramJson(headers, payload);
  }

  @PostMapping("/telegram/{tenantKey}")
  public ChannelMessageResponse telegramLegacy(@PathVariable String tenantKey, @RequestHeader Map<String, String> headers, @RequestBody WebhookPayloadRequest request) {
    JsonNode payload = parsePayload(request.rawPayload());
    return telegramJson(headers, payload, request.externalEventId());
  }

  @PostMapping("/whatsapp")
  public ChannelMessageResponse whatsapp(@RequestHeader Map<String, String> headers, @RequestBody JsonNode payload) {
    return whatsappJson(headers, payload);
  }

  @PostMapping("/whatsapp/{tenantKey}")
  public ChannelMessageResponse whatsappLegacy(@PathVariable String tenantKey, @RequestHeader Map<String, String> headers, @RequestBody WebhookPayloadRequest request) {
    JsonNode payload = parsePayload(request.rawPayload());
    return whatsappJson(headers, payload, request.externalEventId());
  }

  @GetMapping("/events")
  public List<WebhookEventResponse> events() {
    return eventService.list().stream().map(this::toEvent).toList();
  }

  @GetMapping("/events/{id}")
  public WebhookEventResponse event(@PathVariable UUID id) {
    return toEvent(eventService.get(id));
  }

  private ChannelMessageResponse telegramJson(Map<String, String> headers, JsonNode payload) {
    return telegramJson(headers, payload, null);
  }

  private ChannelMessageResponse telegramJson(Map<String, String> headers, JsonNode payload, String suppliedExternalEventId) {
    String rawPayload = writeJson(payload);
    String externalEventId = firstNonBlank(suppliedExternalEventId, text(payload, "update_id"), text(payload.at("/message"), "message_id"));
    JsonNode messageNode = payload.path("message");
    String conversationId = firstNonBlank(text(messageNode.path("chat"), "id"), externalEventId);
    String sender = firstNonBlank(text(messageNode.path("from"), "username"), text(messageNode.path("from"), "id"), "telegram");
    String senderDisplayName = firstNonBlank(text(messageNode.path("from"), "first_name"), text(messageNode.path("chat"), "title"), "Telegram User");
    String body = firstNonBlank(text(messageNode, "text"), rawPayload);
    var verification = verificationService.verify("TELEGRAM", rawPayload, headers);
    requireAccepted(verification);
    eventService.record("TELEGRAM", externalEventId, rawPayload, writeJson(headers), verification.accepted());
    return toMessage(messageService.create(new MessageRequest("TELEGRAM", externalEventId, conversationId, sender, senderDisplayName, null, "INBOUND", "TEXT", body, rawPayload)));
  }

  private ChannelMessageResponse whatsappJson(Map<String, String> headers, JsonNode payload) {
    return whatsappJson(headers, payload, null);
  }

  private ChannelMessageResponse whatsappJson(Map<String, String> headers, JsonNode payload, String suppliedExternalEventId) {
    String rawPayload = writeJson(payload);
    JsonNode messageNode = payload.at("/entry/0/changes/0/value/messages/0");
    JsonNode contactNode = payload.at("/entry/0/changes/0/value/contacts/0");
    String externalEventId = firstNonBlank(suppliedExternalEventId, text(messageNode, "id"), text(payload, "id"));
    String sender = firstNonBlank(text(messageNode, "from"), text(contactNode, "wa_id"), "whatsapp");
    String senderDisplayName = firstNonBlank(text(contactNode.at("/profile"), "name"), sender);
    String body = firstNonBlank(text(messageNode.path("text"), "body"), text(messageNode, "type"), rawPayload);
    var verification = verificationService.verify("WHATSAPP", rawPayload, headers);
    requireAccepted(verification);
    eventService.record("WHATSAPP", externalEventId, rawPayload, writeJson(headers), verification.accepted());
    return toMessage(messageService.create(new MessageRequest("WHATSAPP", externalEventId, sender, sender, senderDisplayName, null, "INBOUND", "TEXT", body, rawPayload)));
  }

  private void saveAttachmentMetadata(ChannelMessage message, List<AttachmentMetadataRequest> attachments) {
    if (attachments == null || attachments.isEmpty()) return;
    UUID tenantId = TenantContext.requireTenantId();
    for (AttachmentMetadataRequest attachment : attachments) {
      attachmentRepository.save(new InboundAttachment(tenantId, message.getId(), null, attachment.originalFilename(), attachment.contentType(), attachment.sizeBytes(), attachment.objectStorageKey(), attachment.fingerprintSha256(), "METADATA_ONLY", clock.instant()));
    }
  }

  private JsonNode parsePayload(String rawPayload) {
    try {
      return rawPayload == null || rawPayload.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(rawPayload);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Webhook rawPayload must be valid JSON");
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Unable to serialize webhook payload");
    }
  }

  private String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? "" : value.asText();
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value;
    }
    return "";
  }

  private void requireAccepted(WebhookVerificationService.WebhookVerificationResult verification) {
    if (!verification.accepted()) throw new IllegalArgumentException(verification.reason());
  }

  private ChannelMessageResponse toMessage(ChannelMessage message) {
    return new ChannelMessageResponse(message.getId(), message.getChannel(), message.getExternalMessageId(), message.getConversationId(), message.getSenderHandle(), message.getMessageType(), message.getTextContent(), message.getStatus(), message.getReceivedAt());
  }

  private WebhookEventResponse toEvent(WebhookEvent event) {
    return new WebhookEventResponse(event.getId(), event.getProvider(), event.getExternalEventId(), event.isSignatureVerified(), event.isReplayDetected(), event.getStatus(), event.getReceivedAt());
  }
}
