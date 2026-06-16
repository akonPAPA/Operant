package com.orderpilot.domain.trust.learning;

/**
 * OP-CAP-18 Operator Correction Learning Loop.
 *
 * The kind of operator correction captured for governed learning. {@code PRICE_OR_STOCK_CORRECTION_BLOCKED}
 * is recorded for traceability but is never eligible to become authoritative AI memory — price/stock stay
 * deterministic and backend-authoritative.
 */
public enum OperatorCorrectionType {
  PRODUCT_ALIAS,
  CUSTOMER_ALIAS,
  DOCUMENT_FIELD_MAPPING,
  PAYMENT_MATCHING_HINT,
  TRUST_REASON_RECLASSIFICATION,
  VALIDATION_RULE_CLARIFICATION,
  BOT_RESPONSE_CORRECTION,
  IMPORT_MAPPING_CORRECTION,
  PRICE_OR_STOCK_CORRECTION_BLOCKED
}
