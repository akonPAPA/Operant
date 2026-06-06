package com.orderpilot.domain.product;

/** OP-CAP-09A how a requested item was resolved to a product. Ordinal encodes strength (higher=stronger). */
public enum ProductMatchType {
  NONE,
  TEXT_CANDIDATE,
  OEM_REFERENCE,
  ALIAS,
  EXACT_SKU
}
