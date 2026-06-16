package com.orderpilot.domain.validation;

/**
 * OP-CAP-07E deterministic validation issue taxonomy for advisory AI extraction results.
 *
 * <p>Narrow, AI-advisory-specific codes. The existing OP-CAP-08A {@code ValidationIssueType} is used
 * internally by the reused {@code ValidationEngineService} for the heavy product/customer/inventory/
 * price resolution; this enum is the stable, bounded surface persisted for the AI-advisory routing
 * layer so its taxonomy is explicit and decoupled from the engine's internal types.
 */
public enum AiValidationIssueCode {
  MISSING_INTENT,
  MISSING_LINE_ITEMS,
  UNKNOWN_CUSTOMER,
  UNKNOWN_PRODUCT,
  INVALID_QUANTITY,
  INVALID_UOM,
  LOW_CONFIDENCE_FIELD,
  PROMPT_INJECTION_SIGNAL,
  UNSUPPORTED_SOURCE_TYPE,
  PROVIDER_FAILURE,
  EXTRACTION_REJECTED,
  INVENTORY_UNKNOWN,
  PRICE_UNKNOWN
}
