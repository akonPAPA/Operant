package com.orderpilot.domain.validation;

/**
 * OP-CAP-07E routing decision produced by deterministic validation of an advisory AI extraction
 * result.
 *
 * <p>{@code READY_FOR_DRAFT_REVIEW} means only that the advisory result is safe enough to become a
 * future draft-workflow candidate — it is NOT a quote/order and creates nothing. Prompt injection,
 * low confidence, unknown customer/product, invalid quantity and provider failures always route away
 * from this state.
 */
public enum AiValidationRoutingDecision {
  READY_FOR_DRAFT_REVIEW,
  NEEDS_HUMAN_REVIEW,
  BLOCKED_INVALID_EXTRACTION,
  FAILED_VALIDATION
}
