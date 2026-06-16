package com.orderpilot.domain.validation;

/**
 * OP-CAP-07E deterministic risk classification for an advisory AI extraction result.
 *
 * <p>Computed by the deterministic validation/risk layer over untrusted AI output. It never
 * authorizes a business mutation; it only routes the advisory result toward draft review or human
 * review.
 */
public enum AiValidationRiskLevel {
  LOW,
  MEDIUM,
  HIGH,
  BLOCKED
}
