package com.orderpilot.application.services.channel;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelIdentity;
import com.orderpilot.domain.channel.ChannelIdentityRepository;
import com.orderpilot.domain.channel.ChannelIdentityResolutionStatus;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-06C identity-aware runtime context.
 *
 * <p>Deterministically resolves a verified inbound channel sender to a tenant-owned customer/contact
 * using the existing {@code channel_identity} mapping (keyed by tenant + channel type + external
 * sender id). It performs no AI/LLM inference and never trusts customer-supplied names; it keys only
 * on the provider-derived external sender id captured on the normalized inbound event.
 *
 * <p>The mapping table enforces a unique row per (tenant, channel type, external sender id), so a
 * sender resolves to at most one identity. "Ambiguous" therefore means a candidate exists that an
 * operator has not yet confirmed (SUGGESTED_MATCH / NEEDS_REVIEW) — not multiple competing rows.
 *
 * <p>This is read-only advisory context. A resolved identity never bypasses OP-CAP-06B configuration
 * or the runtime's own deterministic validation.
 */
@Service
public class ChannelIdentityResolverService {
  private final ChannelIdentityRepository channelIdentityRepository;

  public ChannelIdentityResolverService(ChannelIdentityRepository channelIdentityRepository) {
    this.channelIdentityRepository = channelIdentityRepository;
  }

  @Transactional(readOnly = true)
  public ChannelIdentityResolution resolve(ChannelProviderType providerType, String externalSenderId) {
    UUID tenantId = TenantContext.requireTenantId();
    if (externalSenderId == null || externalSenderId.isBlank()) {
      return new ChannelIdentityResolution(
          ChannelIdentityResolutionStatus.NOT_APPLICABLE, null, null, null, externalSenderId, "NO_EXTERNAL_SENDER_ID");
    }
    Optional<ChannelIdentity> match =
        channelIdentityRepository.findByTenantIdAndChannelTypeAndExternalSenderId(tenantId, providerType.name(), externalSenderId);
    if (match.isEmpty()) {
      return new ChannelIdentityResolution(
          ChannelIdentityResolutionStatus.UNKNOWN, null, null, null, externalSenderId, "NO_MAPPING");
    }
    ChannelIdentity identity = match.get();
    return switch (identity.getIdentityStatus()) {
      case "BLOCKED" -> resolution(ChannelIdentityResolutionStatus.BLOCKED, identity, null, null, "BLOCKED_IDENTITY");
      case "LINKED" -> resolution(ChannelIdentityResolutionStatus.RESOLVED, identity,
          identity.getCustomerAccountId(), identity.getCustomerContactId(), "LINKED");
      case "SUGGESTED_MATCH", "NEEDS_REVIEW" -> resolution(ChannelIdentityResolutionStatus.AMBIGUOUS, identity,
          identity.getCustomerAccountId(), identity.getCustomerContactId(), identity.getIdentityStatus());
      default -> resolution(ChannelIdentityResolutionStatus.UNKNOWN, identity, null, null, "UNLINKED");
    };
  }

  private static ChannelIdentityResolution resolution(
      ChannelIdentityResolutionStatus status, ChannelIdentity identity, UUID accountId, UUID contactId, String reason) {
    return new ChannelIdentityResolution(status, identity.getId(), accountId, contactId, identity.getExternalSenderId(), reason);
  }
}
