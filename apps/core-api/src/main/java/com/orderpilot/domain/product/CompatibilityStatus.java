package com.orderpilot.domain.product;

/** OP-CAP-09A fitment/compatibility status for a product against requested vehicle/equipment context. */
public enum CompatibilityStatus {
  CONFIRMED,
  PARTIAL,
  UNKNOWN,
  CONFLICT
}
