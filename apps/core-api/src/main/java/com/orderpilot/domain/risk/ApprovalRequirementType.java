package com.orderpilot.domain.risk;

/**
 * OP-CAP-08A advisory approval-requirement taxonomy emitted by the deterministic validation engine.
 *
 * <p>Emitting one of these only signals that a human manager gate is required before any later
 * (separately built) commercial action. The engine never approves anything itself.
 */
public enum ApprovalRequirementType {
  MARGIN_GUARDRAIL_APPROVAL,
  DISCOUNT_APPROVAL,
  HIGH_VALUE_APPROVAL,
  // OP-CAP-09A: offering a risky/high-risk substitute requires manager approval before use.
  SUBSTITUTE_APPROVAL
}
