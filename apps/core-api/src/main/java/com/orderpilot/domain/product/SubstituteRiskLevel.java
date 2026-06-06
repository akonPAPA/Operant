package com.orderpilot.domain.product;

/** OP-CAP-09A risk level of offering a substitute. BLOCKED means it must never be auto-offered. */
public enum SubstituteRiskLevel {
  LOW,
  MEDIUM,
  HIGH,
  BLOCKED
}
