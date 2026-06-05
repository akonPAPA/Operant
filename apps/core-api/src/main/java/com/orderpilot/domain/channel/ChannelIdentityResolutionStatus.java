package com.orderpilot.domain.channel;

/**
 * OP-CAP-06C deterministic outcome of resolving an inbound channel sender to a tenant-owned
 * customer/contact via the existing {@code channel_identity} mapping. This is advisory context for
 * the bot runtime policy decision only; a resolved identity never bypasses configuration or
 * deterministic runtime validation.
 */
public enum ChannelIdentityResolutionStatus {
  /** A confirmed (LINKED) mapping exists for this sender. */
  RESOLVED,
  /** A candidate mapping exists but is not confirmed (SUGGESTED_MATCH / NEEDS_REVIEW). */
  AMBIGUOUS,
  /** No mapping, or an UNLINKED mapping, exists for this sender. */
  UNKNOWN,
  /** The mapping is explicitly BLOCKED; the sender must not receive business answers. */
  BLOCKED,
  /** No external sender id was available, so identity resolution does not apply. */
  NOT_APPLICABLE
}
