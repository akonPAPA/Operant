package com.orderpilot.domain.risk;

/**
 * OP-CAP-08A deterministic risk routing decision for an advisory extracted request.
 *
 * <p>This is the route a deterministic validation produces; it is NOT a business approval and never
 * creates a quote/order. Ordinal order encodes escalation strength (later = stronger gate) and is
 * used by the engine to pick the strongest applicable decision.
 */
public enum ValidationRiskDecision {
  AUTO_READY_DRAFT,
  NEEDS_OPERATOR_REVIEW,
  REQUIRES_MANAGER_APPROVAL,
  BLOCKED
}
