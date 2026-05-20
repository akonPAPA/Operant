package com.orderpilot.application.services.channel;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.IntakeValidationService;
import com.orderpilot.application.services.ProcessingJobService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelGatewayService {
  private final ChannelMessageRepository channelMessageRepository;
  private final IntakeValidationService validationService;
  private final ProcessingJobService processingJobService;
  private final AuditEventService auditEventService;
  private final ChannelIdentityService channelIdentityService;
  private final Clock clock;

  public ChannelGatewayService(ChannelMessageRepository channelMessageRepository, IntakeValidationService validationService, ProcessingJobService processingJobService, AuditEventService auditEventService, ChannelIdentityService channelIdentityService, Clock clock) {
    this.channelMessageRepository = channelMessageRepository;
    this.validationService = validationService;
    this.processingJobService = processingJobService;
    this.auditEventService = auditEventService;
    this.channelIdentityService = channelIdentityService;
    this.clock = clock;
  }

  @Transactional
  public ChannelMessage accept(NormalizedInboundMessage inbound) {
    return accept(inbound, WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E);
  }

  @Transactional
  public ChannelMessage accept(NormalizedInboundMessage inbound, WebhookVerificationMode verificationMode) {
    UUID tenantId = TenantContext.requireTenantId();
    if (inbound.tenantId() != null && !tenantId.equals(inbound.tenantId())) {
      throw new IllegalArgumentException("normalized tenant_id must match current tenant context");
    }
    if (inbound.channelType() == null) {
      throw new IllegalArgumentException("channel_type is required");
    }
    validationService.validateMessage(inbound.channelType().name(), inbound.rawText(), inbound.attachmentRefs() != null && !inbound.attachmentRefs().isEmpty());

    String externalMessageId = firstNonBlank(inbound.externalMessageId(), inbound.idempotencyKey());
    if (externalMessageId == null) {
      throw new IllegalArgumentException("external_message_id or idempotency_key is required");
    }
    String channel = inbound.channelType().name();
    var existing = channelMessageRepository.findFirstByTenantIdAndChannelAndExternalMessageId(tenantId, channel, externalMessageId);
    if (existing.isPresent()) {
      auditEventService.record("CHANNEL_GATEWAY_DUPLICATE_IGNORED", "CHANNEL_MESSAGE", existing.get().getId().toString(), null, "{\"channel\":\"" + channel + "\"}");
      return existing.get();
    }

    Instant receivedAt = inbound.receivedAt() == null ? clock.instant() : inbound.receivedAt();
    String conversationId = firstNonBlank(inbound.externalConversationId(), inbound.externalSenderId(), UUID.randomUUID().toString());
    String senderHandle = firstNonBlank(inbound.senderPhone(), inbound.externalSenderId(), inbound.senderDisplayName());
    var identity = channelIdentityService.findOrCreateUnlinkedIdentity(inbound.channelType(), firstNonBlank(inbound.externalSenderId(), senderHandle), conversationId, inbound.senderPhone(), inbound.senderDisplayName());
    String status = identity.isBlocked() ? "BLOCKED_IDENTITY_NEEDS_REVIEW" : identity.isLinked() ? "PENDING_PROCESSING_LINKED_IDENTITY" : "PENDING_REVIEW_UNLINKED_IDENTITY";
    ChannelMessage message = new ChannelMessage(
        tenantId,
        channel,
        externalMessageId,
        conversationId,
        senderHandle,
        inbound.senderDisplayName(),
        identity.getCustomerAccountId(),
        identity.getCustomerContactId(),
        identity.getId(),
        verificationMode == null ? WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E.name() : verificationMode.name(),
        "INBOUND",
        "TEXT",
        inbound.rawText(),
        inbound.rawPayloadJson(),
        status,
        receivedAt);
    ChannelMessage saved = channelMessageRepository.save(message);
    if (!identity.isBlocked()) {
      processingJobService.enqueue(tenantId, "MESSAGE_PROCESSING", "CHANNEL_MESSAGE", saved.getId());
    }
    auditEventService.record("CHANNEL_GATEWAY_MESSAGE_RECEIVED", "CHANNEL_MESSAGE", saved.getId().toString(), null, "{\"channel\":\"" + channel + "\",\"externalExecution\":\"DISABLED\",\"identityStatus\":\"" + identity.getIdentityStatus() + "\"}");
    return saved;
  }

  @Transactional(readOnly = true)
  public List<ChannelMessage> listMessages() {
    return channelMessageRepository.findByTenantIdOrderByReceivedAtDesc(TenantContext.requireTenantId());
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
