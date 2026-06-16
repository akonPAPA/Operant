package com.orderpilot.domain.trust;

/**
 * OP-CAP-17D Trust Risk Decision Engine.
 *
 * Deterministic routing action attached to a {@link TrustRiskDecision}. This is an operator-facing
 * routing outcome, never a legal fraud verdict. Ordering is informational only — the authoritative
 * gate is {@link TrustRiskLevel} plus the decision's {@code blocking}/{@code humanReviewRequired}
 * flags.
 */
public enum TrustRiskAction {
  /** LOW: continue normally; no approval required; non-blocking. */
  CONTINUE,
  /** MEDIUM: continue with warnings; no approval required by default; non-blocking. */
  CONTINUE_WITH_WARNING,
  /** HIGH: draft/review may continue, but an irreversible commit/export requires human approval. */
  REQUIRE_APPROVAL,
  /** CRITICAL: automation/finalization/export is blocked. */
  BLOCK_AUTOMATION,
  /** CRITICAL: blocked and routed to manager/security escalation. */
  ESCALATE
}
