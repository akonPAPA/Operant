package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelIdentityResolutionStatus;
import java.util.UUID;

/**
 * OP-CAP-06C immutable result of resolving an inbound channel sender to a tenant-owned
 * customer/contact. Carries only safe identifiers — never raw sender phone/name or any secret.
 */
public record ChannelIdentityResolution(
    ChannelIdentityResolutionStatus status,
    UUID channelIdentityId,
    UUID customerAccountId,
    UUID customerContactId,
    String externalSenderId,
    String reason) {

  public boolean isResolved() {
    return status == ChannelIdentityResolutionStatus.RESOLVED;
  }

  public boolean isBlocked() {
    return status == ChannelIdentityResolutionStatus.BLOCKED;
  }

  public boolean isAmbiguous() {
    return status == ChannelIdentityResolutionStatus.AMBIGUOUS;
  }
}
