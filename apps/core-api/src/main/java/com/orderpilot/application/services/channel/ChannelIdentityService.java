package com.orderpilot.application.services.channel;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelIdentity;
import com.orderpilot.domain.channel.ChannelIdentityRepository;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.customer.CustomerContact;
import com.orderpilot.domain.customer.CustomerContactRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelIdentityService {
  private final ChannelIdentityRepository repository;
  private final CustomerAccountRepository customerAccountRepository;
  private final CustomerContactRepository customerContactRepository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public ChannelIdentityService(
      ChannelIdentityRepository repository,
      CustomerAccountRepository customerAccountRepository,
      CustomerContactRepository customerContactRepository,
      AuditEventService auditEventService,
      Clock clock) {
    this.repository = repository;
    this.customerAccountRepository = customerAccountRepository;
    this.customerContactRepository = customerContactRepository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public ChannelIdentity findOrCreateUnlinkedIdentity(
      ChannelType channelType,
      String externalSenderId,
      String externalConversationId,
      String senderPhone,
      String senderDisplayName) {
    UUID tenantId = TenantContext.requireTenantId();
    if (channelType == null) throw new IllegalArgumentException("channel_type is required");
    if (isBlank(externalSenderId)) throw new IllegalArgumentException("external_sender_id is required");
    String channel = channelType.name();
    ChannelIdentity identity =
        repository
            .findByTenantIdAndChannelTypeAndExternalSenderId(tenantId, channel, externalSenderId)
            .orElseGet(
                () ->
                    repository.save(
                        new ChannelIdentity(
                            tenantId, channel, externalSenderId, externalConversationId,
                            senderPhone, senderDisplayName, clock.instant())));
    identity.refreshInboundContext(externalConversationId, senderPhone, senderDisplayName, clock.instant());
    return repository.save(identity);
  }

  @Transactional
  public ChannelIdentity suggestCustomerMatch(
      UUID id,
      UUID customerAccountId,
      UUID customerContactId,
      BigDecimal matchConfidence,
      String notes) {
    ChannelIdentity identity = getIdentity(id);
    String previousStatus = identity.getIdentityStatus();
    identity.suggestMatch(customerAccountId, customerContactId, matchConfidence, notes, clock.instant());
    ChannelIdentity saved = repository.save(identity);
    auditEventService.record(
        "CHANNEL_IDENTITY_MATCH_SUGGESTED", "CHANNEL_IDENTITY",
        saved.getId().toString(), null, auditJson(previousStatus, saved));
    return saved;
  }

  /**
   * OP-CAP-06D/06D.1 operator command: confirm/link this identity to a tenant-owned customer
   * account and/or contact. Full tenant-scoped validation is applied before any mutation:
   *
   * <ul>
   *   <li>If contactId provided: contact must exist for this tenant; contactId must belong to the
   *       specified accountId if both are given; accountId is derived from the contact otherwise.
   *   <li>If only accountId provided: account must exist for this tenant.
   *   <li>Cross-tenant IDs are rejected before any write.
   *   <li>Idempotent when the same (resolvedAccountId, contactId) pair is repeated.
   * </ul>
   */
  @Transactional
  public ChannelIdentity linkIdentity(
      UUID id,
      UUID customerAccountId,
      UUID customerContactId,
      UUID linkedByUserId,
      String notes) {
    if (customerAccountId == null && customerContactId == null) {
      throw new IllegalArgumentException("customer_account_id or customer_contact_id is required");
    }
    UUID tenantId = TenantContext.requireTenantId();

    UUID resolvedAccountId;
    if (customerContactId != null) {
      // Validate contact belongs to this tenant; derive account from validated contact.
      CustomerContact contact =
          customerContactRepository
              .findByIdAndTenantIdAndDeletedAtIsNull(customerContactId, tenantId)
              .orElseThrow(() -> new IllegalArgumentException("customer_contact_id not found for this tenant"));
      // If accountId was also supplied, it must match the contact's own account.
      if (customerAccountId != null && !contact.getCustomerAccountId().equals(customerAccountId)) {
        throw new IllegalArgumentException(
            "customer_contact_id does not belong to the specified customer_account_id");
      }
      resolvedAccountId = contact.getCustomerAccountId();
    } else {
      // Account-only link — validate the account belongs to this tenant.
      if (customerAccountRepository
          .findByIdAndTenantIdAndDeletedAtIsNull(customerAccountId, tenantId)
          .isEmpty()) {
        throw new IllegalArgumentException("customer_account_id not found for this tenant");
      }
      resolvedAccountId = customerAccountId;
    }

    ChannelIdentity identity = getIdentityForMutation(id, tenantId);
    // Idempotency: repeating the same (account, contact) pair returns current state without re-auditing.
    if ("LINKED".equals(identity.getIdentityStatus())
        && Objects.equals(identity.getCustomerAccountId(), resolvedAccountId)
        && Objects.equals(identity.getCustomerContactId(), customerContactId)) {
      return identity;
    }
    String previousStatus = identity.getIdentityStatus();
    identity.link(resolvedAccountId, customerContactId, linkedByUserId, notes, clock.instant());
    ChannelIdentity saved = repository.save(identity);
    auditEventService.record(
        "CHANNEL_IDENTITY_LINKED", "CHANNEL_IDENTITY",
        saved.getId().toString(), linkedByUserId, auditJson(previousStatus, saved));
    return saved;
  }

  /** OP-CAP-06D operator command: unlink/reset this identity. Idempotent when already unlinked. */
  @Transactional
  public ChannelIdentity unlinkIdentity(UUID id, String notes) {
    UUID tenantId = TenantContext.requireTenantId();
    ChannelIdentity identity = getIdentityForMutation(id, tenantId);
    if ("UNLINKED".equals(identity.getIdentityStatus())) {
      return identity;
    }
    String previousStatus = identity.getIdentityStatus();
    identity.unlink(notes, clock.instant());
    ChannelIdentity saved = repository.save(identity);
    auditEventService.record(
        "CHANNEL_IDENTITY_UNLINKED", "CHANNEL_IDENTITY",
        saved.getId().toString(), null, auditJson(previousStatus, saved));
    return saved;
  }

  /** OP-CAP-06D operator command: block this identity. Idempotent when already blocked. */
  @Transactional
  public ChannelIdentity blockIdentity(UUID id, String notes) {
    UUID tenantId = TenantContext.requireTenantId();
    ChannelIdentity identity = getIdentityForMutation(id, tenantId);
    if ("BLOCKED".equals(identity.getIdentityStatus())) {
      return identity;
    }
    String previousStatus = identity.getIdentityStatus();
    identity.block(notes, clock.instant());
    ChannelIdentity saved = repository.save(identity);
    auditEventService.record(
        "CHANNEL_IDENTITY_BLOCKED", "CHANNEL_IDENTITY",
        saved.getId().toString(), null, auditJson(previousStatus, saved));
    return saved;
  }

  /**
   * OP-CAP-06D operator command: mark this identity as needing manual review. The bot runtime sees
   * this as AMBIGUOUS (via ChannelIdentityResolverService). Idempotent when already needs review.
   */
  @Transactional
  public ChannelIdentity markNeedsReview(UUID id, String notes) {
    UUID tenantId = TenantContext.requireTenantId();
    ChannelIdentity identity = getIdentityForMutation(id, tenantId);
    if ("NEEDS_REVIEW".equals(identity.getIdentityStatus())) {
      return identity;
    }
    String previousStatus = identity.getIdentityStatus();
    identity.needsReview(notes, clock.instant());
    ChannelIdentity saved = repository.save(identity);
    auditEventService.record(
        "CHANNEL_IDENTITY_NEEDS_REVIEW", "CHANNEL_IDENTITY",
        saved.getId().toString(), null, auditJson(previousStatus, saved));
    return saved;
  }

  @Transactional(readOnly = true)
  public ChannelIdentity getIdentity(UUID id) {
    return repository
        .findByIdAndTenantId(id, TenantContext.requireTenantId())
        .orElseThrow(() -> new IllegalArgumentException("Channel identity not found"));
  }

  @Transactional(readOnly = true)
  public List<ChannelIdentity> listIdentities() {
    return repository.findByTenantIdOrderByUpdatedAtDesc(TenantContext.requireTenantId());
  }

  /** Pessimistic-write locked fetch for operator mutation commands. */
  private ChannelIdentity getIdentityForMutation(UUID id, UUID tenantId) {
    return repository
        .findWithLockByIdAndTenantId(id, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("Channel identity not found"));
  }

  /** Safe audit metadata — contains only IDs and status strings, never secrets or PII. */
  private static String auditJson(String previousStatus, ChannelIdentity identity) {
    return "{\"channelIdentityId\":\"" + identity.getId()
        + "\",\"channelType\":\"" + identity.getChannelType()
        + "\",\"previousStatus\":\"" + safe(previousStatus)
        + "\",\"newStatus\":\"" + identity.getIdentityStatus()
        + "\",\"customerAccountId\":\"" + str(identity.getCustomerAccountId())
        + "\",\"customerContactId\":\"" + str(identity.getCustomerContactId()) + "\"}";
  }

  private static String str(UUID value) {
    return value == null ? "" : value.toString();
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
