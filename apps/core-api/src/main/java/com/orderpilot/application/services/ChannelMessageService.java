package com.orderpilot.application.services;

import com.orderpilot.api.dto.Stage3Dtos.MessageRequest;
import com.orderpilot.common.api.TenantScopedListLimits;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.InboundEventLedger;
import com.orderpilot.domain.intake.InboundEventLedgerRepository;
import com.orderpilot.domain.intake.ObjectStorageRecord;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelMessageService {
  private final ChannelMessageRepository repository; private final InboundEventLedgerRepository ledgerRepository; private final IntakeValidationService validationService; private final ProcessingJobService jobService; private final AuditEventService auditEventService; private final ObjectStorageService storageService; private final Clock clock;
  public ChannelMessageService(ChannelMessageRepository repository, InboundEventLedgerRepository ledgerRepository, IntakeValidationService validationService, ProcessingJobService jobService, AuditEventService auditEventService, ObjectStorageService storageService, Clock clock){this.repository=repository; this.ledgerRepository=ledgerRepository; this.validationService=validationService; this.jobService=jobService; this.auditEventService=auditEventService; this.storageService=storageService; this.clock=clock;}
  @Transactional(readOnly=true) public List<ChannelMessage> list(){ return list(TenantScopedListLimits.GENERAL_LIST_DEFAULT); }
  @Transactional(readOnly=true) public List<ChannelMessage> list(int limit){
    int clamped = TenantScopedListLimits.clamp(limit, TenantScopedListLimits.GENERAL_LIST_DEFAULT, TenantScopedListLimits.GENERAL_LIST_MAX);
    return repository.findByTenantIdOrderByReceivedAtDesc(TenantContext.requireTenantId(), PageRequest.of(0, clamped));
  }
  @Transactional(readOnly=true) public ChannelMessage get(UUID id){ return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Channel message not found")); }
  @Transactional(readOnly=true) public List<ChannelMessage> conversation(String conversationId){ return conversation(conversationId, TenantScopedListLimits.GENERAL_LIST_DEFAULT); }
  @Transactional(readOnly=true) public List<ChannelMessage> conversation(String conversationId, int limit){
    int clamped = TenantScopedListLimits.clamp(limit, TenantScopedListLimits.GENERAL_LIST_DEFAULT, TenantScopedListLimits.GENERAL_LIST_MAX);
    return repository.findByTenantIdAndConversationIdOrderByReceivedAt(TenantContext.requireTenantId(), conversationId, PageRequest.of(0, clamped));
  }
  @Transactional public ChannelMessage create(MessageRequest request) {
    UUID tenantId = TenantContext.requireTenantId(); validationService.validateMessage(request.channel(), request.textContent(), false);
    if (request.externalMessageId()!=null && !request.externalMessageId().isBlank()) {
      var existing = repository.findFirstByTenantIdAndChannelAndExternalMessageId(tenantId, request.channel(), request.externalMessageId());
      if (existing.isPresent()) { ObjectStorageRecord duplicatePayload = storageService.storeRawPayload(request.channel(), request.externalMessageId(), request.rawPayload() == null || request.rawPayload().isBlank() ? "{}" : request.rawPayload()); ChannelMessage duplicate = new ChannelMessage(tenantId, request.channel(), request.externalMessageId(), request.conversationId(), request.senderHandle(), request.senderDisplayName(), request.customerAccountId(), request.direction()==null?"INBOUND":request.direction(), request.messageType()==null?"TEXT":request.messageType(), request.textContent(), normalizeText(request.textContent()), request.rawPayload(), duplicatePayload.getObjectKey(), "DUPLICATE", clock.instant()); ChannelMessage saved=repository.save(duplicate); ledgerRepository.save(new InboundEventLedger(tenantId, request.channel(), request.externalMessageId(), "MESSAGE_RECEIVED", duplicatePayload.getSha256Fingerprint(), "DUPLICATE", duplicatePayload.getObjectKey(), clock.instant())); auditEventService.record("channel_message.duplicate", "channel_message", saved.getId().toString(), null, "{\"source\":\"intake\"}"); return saved; }
    }
    String conversationId = request.conversationId()==null || request.conversationId().isBlank() ? UUID.randomUUID().toString() : request.conversationId();
    String rawPayload = request.rawPayload() == null || request.rawPayload().isBlank() ? "{}" : request.rawPayload();
    ObjectStorageRecord storedPayload = storageService.storeRawPayload(request.channel(), request.externalMessageId(), rawPayload);
    String rawPayloadStorageKey = storedPayload.getObjectKey();
    ChannelMessage msg = new ChannelMessage(tenantId, request.channel(), request.externalMessageId(), conversationId, request.senderHandle(), request.senderDisplayName(), request.customerAccountId(), request.direction()==null?"INBOUND":request.direction(), request.messageType()==null?"TEXT":request.messageType(), request.textContent(), normalizeText(request.textContent()), rawPayload, rawPayloadStorageKey, "QUEUED", clock.instant());
    ChannelMessage saved = repository.save(msg); ledgerRepository.save(new InboundEventLedger(tenantId, request.channel(), request.externalMessageId(), "MESSAGE_RECEIVED", storedPayload.getSha256Fingerprint(), "QUEUED", rawPayloadStorageKey, clock.instant())); jobService.enqueue(tenantId, "MESSAGE_RECEIVED", "CHANNEL_MESSAGE", saved.getId()); auditEventService.record("channel_message.received", "channel_message", saved.getId().toString(), null, "{\"source\":\"intake\"}"); return saved;
  }

  private String normalizeText(String value) {
    return value == null ? null : value.trim().replaceAll("\\s+", " ");
  }
}
