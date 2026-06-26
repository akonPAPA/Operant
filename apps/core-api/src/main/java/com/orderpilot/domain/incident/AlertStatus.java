package com.orderpilot.domain.incident;

/**
 * OP-CAP-53 — record-only alert status. An alert is born {@code PENDING_DISPATCH}: it records the intent to
 * notify, but no delivery channel is wired in this stage, so it is never actually sent. A real notification
 * transport (and a transition to a DELIVERED/FAILED state) is deferred to a later stage.
 */
public enum AlertStatus {
  PENDING_DISPATCH
}
