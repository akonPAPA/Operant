package com.orderpilot.domain.validation;

public enum ValidationIssueType {
  INVALID_QUANTITY,
  REQUESTED_DATE_INVALID,
  LOW_EXTRACTION_CONFIDENCE,
  PRODUCT_ALIAS_MATCHED,
  OEM_MATCHED,
  PRODUCT_AMBIGUOUS,
  PRODUCT_NOT_FOUND,
  INVALID_UOM,
  UOM_NORMALIZED,
  NEEDS_HUMAN_REVIEW,
  OUT_OF_STOCK,
  LOW_STOCK,
  PRICE_NOT_FOUND,
  MARGIN_BELOW_GUARDRAIL,
  SUBSTITUTE_REQUIRED,
  SUBSTITUTE_AVAILABLE,
  // OP-CAP-08A additions for the deterministic validation/risk engine. Additive only: this enum is
  // not exhaustively switched anywhere (persisted issues use String types), so adding values is safe
  // and does not affect the existing persisted run pipeline. Existing equivalents are reused
  // (INVALID_QUANTITY, INVALID_UOM, PRODUCT_ALIAS_MATCHED, OEM_MATCHED, ...) rather than duplicated.
  CUSTOMER_NOT_FOUND,
  CUSTOMER_AMBIGUOUS,
  INVENTORY_UNAVAILABLE,
  INVENTORY_STALE,
  DISCOUNT_REQUIRES_APPROVAL,
  SUBSTITUTE_BLOCKED,
  PROMPT_INJECTION_FLAGGED,
  UNSUPPORTED_INTENT,
  HIGH_VALUE_REQUIRES_APPROVAL,
  // OP-CAP-09A product intelligence + substitution foundation. Additive only (see note above).
  SUBSTITUTE_CANDIDATE_FOUND,
  SUBSTITUTE_REQUIRES_APPROVAL,
  COMPATIBILITY_UNKNOWN,
  COMPATIBILITY_CONFLICT,
  CUSTOMER_SUBSTITUTE_BLOCKED,
  PRODUCT_TEXT_CANDIDATE_LOW_CONFIDENCE
}
