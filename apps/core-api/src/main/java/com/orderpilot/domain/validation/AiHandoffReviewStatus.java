package com.orderpilot.domain.validation;

/**
 * OP-CAP-08B operator review lifecycle status for an AI validation handoff.
 *
 * <p>This is the review-workflow state, kept separate from the 07F routing decision / handoff
 * status. Only {@code DRAFT_PREPARATION_READY} unlocks the draft-preparation candidate contract,
 * and even that creates no quote/order/draft and no external write.
 */
public enum AiHandoffReviewStatus {
  PENDING_REVIEW,
  IN_REVIEW,
  CORRECTION_REQUESTED,
  DRAFT_PREPARATION_READY,
  BLOCKED,
  DISMISSED,
  FAILED;

  public boolean isTerminal() {
    return this == DRAFT_PREPARATION_READY || this == BLOCKED || this == DISMISSED || this == FAILED;
  }
}
