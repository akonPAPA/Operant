package com.orderpilot.domain.trust;

/**
 * OP-CAP-17D Trust Risk Decision Engine.
 *
 * Lifecycle status of a {@link TrustRiskDecision}. {@code ACTIVE} is the current decision for its
 * subject. {@code SUPERSEDED} is set when a newer evaluation replaces it. {@code OVERRIDDEN} is set
 * when an authorized operator manually overrides it (the original evidence is never deleted).
 * {@code CANCELLED} is a terminal void state.
 */
public enum TrustRiskDecisionStatus {
  ACTIVE,
  SUPERSEDED,
  OVERRIDDEN,
  CANCELLED
}
