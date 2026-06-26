package com.orderpilot.domain.incident;

/**
 * OP-CAP-53 — record-only alert/notification kinds. These record that a security/platform alert SHOULD be
 * emitted later; this stage performs no external delivery (no email/SMS/Slack, no network call).
 */
public enum AlertType {
  CRITICAL_INCIDENT_CREATED,
  BREAK_GLASS_REQUESTED,
  BREAK_GLASS_APPROVED,
  BREAK_GLASS_REJECTED,
  BREAK_GLASS_REVOKED,
  BREAK_GLASS_EXPIRED
}
