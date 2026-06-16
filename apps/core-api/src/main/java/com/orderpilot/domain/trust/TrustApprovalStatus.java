package com.orderpilot.domain.trust;

/**
 * OP-CAP-17D Trust Risk Decision Engine.
 *
 * Lifecycle status of a {@link TrustApprovalRequirement}. {@code PENDING} blocks the irreversible
 * action until an authorized human {@code SATISFIED}s or {@code WAIVED}s it. {@code CANCELLED} voids
 * the requirement when its decision is superseded/cancelled.
 */
public enum TrustApprovalStatus {
  PENDING,
  SATISFIED,
  WAIVED,
  CANCELLED
}
