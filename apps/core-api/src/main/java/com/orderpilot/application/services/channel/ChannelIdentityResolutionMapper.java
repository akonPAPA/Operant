package com.orderpilot.application.services.channel;

import com.orderpilot.api.dto.Stage10DOmnichannelDtos.ChannelIdentityResolutionView;
import com.orderpilot.domain.channel.ChannelIdentity;

/**
 * OP-CAP-06D deterministic mapping from the domain {@link ChannelIdentity} status to the stable
 * frontend-facing {@link ChannelIdentityResolutionView}. Pure static logic; no Spring bean.
 *
 * <p>Mapping table (aligns exactly with {@code ChannelIdentityResolverService}):
 * <ul>
 *   <li>LINKED → RESOLVED (customerAccountId/contactId included)</li>
 *   <li>SUGGESTED_MATCH / NEEDS_REVIEW → AMBIGUOUS (candidate ids included)</li>
 *   <li>UNLINKED / unknown → UNKNOWN (no customer ids)</li>
 *   <li>BLOCKED → BLOCKED (no customer ids exposed)</li>
 *   <li>blank externalSenderId → NOT_APPLICABLE</li>
 * </ul>
 */
public final class ChannelIdentityResolutionMapper {
  private ChannelIdentityResolutionMapper() {}

  public static ChannelIdentityResolutionView toResolutionView(ChannelIdentity identity) {
    String senderId = identity.getExternalSenderId();
    if (senderId == null || senderId.isBlank()) {
      return new ChannelIdentityResolutionView(
          "NOT_APPLICABLE", identity.getId(), null, null, null, "NO_EXTERNAL_SENDER_ID", identity.getUpdatedAt());
    }
    return switch (identity.getIdentityStatus()) {
      case "LINKED" -> new ChannelIdentityResolutionView(
          "RESOLVED", identity.getId(),
          identity.getCustomerAccountId(), identity.getCustomerContactId(),
          senderId, "LINKED_CUSTOMER_CONTACT", identity.getUpdatedAt());
      case "SUGGESTED_MATCH" -> new ChannelIdentityResolutionView(
          "AMBIGUOUS", identity.getId(),
          identity.getCustomerAccountId(), identity.getCustomerContactId(),
          senderId, "SUGGESTED_MATCH", identity.getUpdatedAt());
      case "NEEDS_REVIEW" -> new ChannelIdentityResolutionView(
          "AMBIGUOUS", identity.getId(),
          identity.getCustomerAccountId(), identity.getCustomerContactId(),
          senderId, "NEEDS_REVIEW", identity.getUpdatedAt());
      case "BLOCKED" -> new ChannelIdentityResolutionView(
          "BLOCKED", identity.getId(), null, null, senderId, "BLOCKED_IDENTITY", identity.getUpdatedAt());
      default -> new ChannelIdentityResolutionView(
          "UNKNOWN", identity.getId(), null, null, senderId, "UNLINKED", identity.getUpdatedAt());
    };
  }
}
