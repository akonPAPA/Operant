package com.orderpilot.domain.validation;

/**
 * OP-CAP-07F status of an AI validation handoff work item.
 *
 * <p>Mirrors {@link AiValidationRoutingDecision} 1:1 — the handoff is the operator-facing projection
 * of a deterministic routing decision. Only {@code READY_FOR_DRAFT_REVIEW} is draft-eligible; the
 * others are visible for review/audit but never eligible for draft preparation. A handoff is NEVER a
 * quote/order and never authorizes a business mutation.
 */
public enum AiValidationHandoffStatus {
  READY_FOR_DRAFT_REVIEW,
  NEEDS_HUMAN_REVIEW,
  BLOCKED_INVALID_EXTRACTION,
  FAILED_VALIDATION;

  /** Only a draft-review-ready handoff may become a future draft-preparation candidate. */
  public boolean isDraftEligible() {
    return this == READY_FOR_DRAFT_REVIEW;
  }
}
