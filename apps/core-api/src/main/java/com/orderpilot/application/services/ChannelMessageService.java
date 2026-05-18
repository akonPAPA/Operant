package com.orderpilot.application.services;

import com.orderpilot.api.dto.Stage3Dtos.MessageRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelMessageService {
  private final ChannelMessageRepository repository; private final IntakeValidationService validationService; private final ProcessingJobService jobService; private final AuditEventService auditEventService; private final Clock clock;
  public ChannelMessageService(ChannelMessageRepository repository, IntakeValidationService validationService, ProcessingJobService jobService, AuditEventService auditEventService, Clock clock){this.repository=repository; this.validationService=validationService; this.jobService=jobService; this.auditEventService=auditEventService; this.clock=clock;}
  @Transactional(readOnly=true) public List<ChannelMessage> list(){ return repository.findByTenantIdOrderByReceivedAtDesc(TenantContext.requireTenantId()); }
  @Transactional(readOnly=true) public ChannelMessage get(UUID id){ return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Channel message not found")); }
  @Transactional(readOnly=true) public List<ChannelMessage> conversation(String conversationId){ return repository.findByTenantIdAndConversationIdOrderByReceivedAt(TenantContext.requireTenantId(), conversationId); }
  @Transactional public ChannelMessage create(MessageRequest request) {
    UUID tenantId = TenantContext.requireTenantId(); validationService.validateMessage(request.channel(), request.textContent(), false);
    if (request.externalMessageId()!=null && !request.externalMessageId().isBlank()) {
      var existing = repository.findFirstByTenantIdAndChannelAndExternalMessageId(tenantId, request.channel(), request.externalMessageId());
      if (existing.isPresent()) { ChannelMessage duplicate = new ChannelMessage(tenantId, request.channel(), request.externalMessageId(), request.conversationId(), request.senderHandle(), request.senderDisplayName(), request.customerAccountId(), request.direction()==null?"INBOUND":request.direction(), request.messageType()==null?"TEXT":request.messageType(), request.textContent(), request.rawPayload(), "DUPLICATE", clock.instant()); ChannelMessage saved=repository.save(duplicate); auditEventService.record("channel_message.duplicate", "channel_message", saved.getId().toString(), null, "{\"source\":\"intake\"}"); return saved; }
    }
    String conversationId = request.conversationId()==null || request.conversationId().isBlank() ? UUID.randomUUID().toString() : request.conversationId();
    ChannelMessage msg = new ChannelMessage(tenantId, request.channel(), request.externalMessageId(), conversationId, request.senderHandle(), request.senderDisplayName(), request.customerAccountId(), request.direction()==null?"INBOUND":request.direction(), request.messageType()==null?"TEXT":request.messageType(), request.textContent(), request.rawPayload(), "QUEUED", clock.instant());
    ChannelMessage saved = repository.save(msg); jobService.enqueue(tenantId, "MESSAGE_PROCESSING", "CHANNEL_MESSAGE", saved.getId()); auditEventService.record("channel_message.received", "channel_message", saved.getId().toString(), null, "{\"source\":\"intake\"}"); return saved;
  }
}