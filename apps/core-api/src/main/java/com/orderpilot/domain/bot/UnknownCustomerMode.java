package com.orderpilot.domain.bot;

/** OP-CAP-06B behavior when the inbound sender cannot be resolved to a known customer. */
public enum UnknownCustomerMode {
  /** Route to a human operator handoff/review. */
  HANDOFF,
  /** Return a safe generic reply that discloses no privileged data. */
  SAFE_GENERIC_REPLY,
  /** Reject the request with an audited safe message. */
  REJECT
}
