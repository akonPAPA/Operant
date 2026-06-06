package com.orderpilot.domain.product;

/** OP-CAP-09A advisory reason a substitute was suggested. Explanatory only; never an approval. */
public enum SubstituteReason {
  EXACT_REPLACEMENT,
  OEM_EQUIVALENT,
  COMPATIBLE_WITH_MODEL,
  COMPATIBLE_WITH_CONFIGURATION,
  CUSTOMER_ACCEPTED_BEFORE,
  STOCK_PREFERRED,
  MARGIN_PREFERRED,
  BLOCKED_BY_CUSTOMER_RULE,
  LOW_EVIDENCE
}
