package com.orderpilot.domain.trust.learning;

/**
 * OP-CAP-18 Operator Correction Learning Loop.
 *
 * Lifecycle of an operator correction learning record. {@code RECORDED} → {@code APPROVED_FOR_LEARNING}
 * (operator-gated) → {@code PROJECTED_TO_MEMORY} (after the projector creates governed memory). A record
 * may instead be {@code REJECTED} or {@code SUPERSEDED}.
 */
public enum OperatorCorrectionStatus {
  RECORDED,
  APPROVED_FOR_LEARNING,
  PROJECTED_TO_MEMORY,
  REJECTED,
  SUPERSEDED
}
