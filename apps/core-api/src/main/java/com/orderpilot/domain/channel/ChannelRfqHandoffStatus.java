package com.orderpilot.domain.channel;

/**
 * OP-CAP-06B controlled Bot Runtime RFQ Handoff lifecycle.
 *
 * <p>A {@link ChannelRfqHandoff} is an internal, reviewable draft request only. It is never a quote
 * or order, and the bot/channel path may only create it in {@link #PENDING_REVIEW}. Operator-driven
 * transitions (review, conversion, dismissal) are out of scope for this slice but the allowed
 * vocabulary is fixed here so the persisted status can never drift into an uncontrolled value.
 */
public enum ChannelRfqHandoffStatus {
  PENDING_REVIEW,
  IN_REVIEW,
  CONVERTED,
  DISMISSED
}
