package com.orderpilot.domain.validation;

/**
 * OP-CAP-08B operator review decision on an AI validation handoff.
 *
 * <p>A decision records operator intent only. {@code APPROVE_FOR_DRAFT_PREPARATION} merely flags the
 * handoff as a future draft candidate through review status {@code DRAFT_PREPARATION_READY}; it
 * never creates a quote/order/draft and never triggers an external write.
 */
public enum AiHandoffReviewDecision {
  APPROVE_FOR_DRAFT_PREPARATION,
  REQUEST_CORRECTION,
  DISMISS_INVALID,
  BLOCK_RISK,
  KEEP_FOR_HUMAN_REVIEW
}
