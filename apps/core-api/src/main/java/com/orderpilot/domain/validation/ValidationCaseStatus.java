package com.orderpilot.domain.validation;

/**
 * OP-CAP-08A overall status of a deterministic validation result.
 *
 * <p>Used as an advisory, computed status on the engine result. This slice does not persist a
 * validation case; {@code DRAFT} is reserved for a future persisted-case lifecycle.
 */
public enum ValidationCaseStatus {
  DRAFT,
  VALIDATED,
  NEEDS_REVIEW,
  BLOCKED
}
