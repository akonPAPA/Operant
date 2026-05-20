package com.orderpilot.application.services.channel;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelIdentity;
import com.orderpilot.domain.channel.ChannelIdentityRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelIdentityService {
  private final ChannelIdentityRepository repository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public ChannelIdentityService(ChannelIdentityRepository repository, AuditEventService auditEventService, Clock clock) {
    this.repository = repository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public ChannelIdentity findOrCreateUnlinkedIdentity(ChannelType channelType, String externalSenderId, String externalConversationId, String senderPhone, String senderDisplayName) {
    UUID tenantId = TenantContext.requireTenantId();
    if (channelType == null) throw new IllegalArgumentException("channel_type is required");
    if (isBlank(externalSenderId)) throw new IllegalArgumentException("external_sender_id is required");
    String channel = channelType.name();
    ChannelIdentity identity = repository.findByTenantIdAndChannelTypeAndExternalSenderId(tenantId, channel, externalSenderId)
        .orElseGet(() -> repository.save(new ChannelIdentity(tenantId, channel, externalSenderId, externalConversationId, senderPhone, senderDisplayName, clock.instant())));
    identity.refreshInboundContext(externalConversationId, senderPhone, senderDisplayName, clock.instant());
    return repository.save(identity);
  }

  @Transactional
  public ChannelIdentity suggestCustomerMatch(UUID id, UUID customerAccountId, UUID customerContactId, BigDecimal matchConfidence, String notes) {
    ChannelIdentity identity = getIdentity(id);
    identity.suggestMatch(customerAccountId, customerContactId, matchConfidence, notes, clock.instant());
    auditEventService.record("CHANNEL_IDENTITY_MATCH_SUGGESTED", "CHANNEL_IDENTITY", identity.getId().toString(), null, "{}");
    return repository.save(identity);
  }

  @Transactional
  public ChannelIdentity linkIdentity(UUID id, UUID customerAccountId, UUID customerContactId, UUID linkedByUserId, String notes) {
    if (customerAccountId == null && customerContactId == null) {
      throw new IllegalArgumentException("customer_account_id or customer_contact_id is required");
    }
    ChannelIdentity identity = getIdentity(id);
    identity.link(customerAccountId, customerContactId, linkedByUserId, notes, clock.instant());
    auditEventService.record("CHANNEL_IDENTITY_LINKED", "CHANNEL_IDENTITY", identity.getId().toString(), linkedByUserId, "{}");
    return repository.save(identity);
  }

  @Transactional
  public ChannelIdentity unlinkIdentity(UUID id, String notes) {
    ChannelIdentity identity = getIdentity(id);
    identity.unlink(notes, clock.instant());
    auditEventService.record("CHANNEL_IDENTITY_UNLINKED", "CHANNEL_IDENTITY", identity.getId().toString(), null, "{}");
    return repository.save(identity);
  }

  @Transactional
  public ChannelIdentity blockIdentity(UUID id, String notes) {
    ChannelIdentity identity = getIdentity(id);
    identity.block(notes, clock.instant());
    auditEventService.record("CHANNEL_IDENTITY_BLOCKED", "CHANNEL_IDENTITY", identity.getId().toString(), null, "{}");
    return repository.save(identity);
  }

  @Transactional(readOnly = true)
  public ChannelIdentity getIdentity(UUID id) {
    return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Channel identity not found"));
  }

  @Transactional(readOnly = true)
  public List<ChannelIdentity> listIdentities() {
    return repository.findByTenantIdOrderByUpdatedAtDesc(TenantContext.requireTenantId());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
