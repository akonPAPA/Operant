package com.orderpilot.domain.trust;

/**
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * Deterministic, explainable risk level for an inbound document trust run. This is NOT a fraud
 * verdict — it is an operator-facing risk indicator. Ordering matters: higher ordinal = higher risk.
 * Policy must never downgrade a higher level once reached (no historical trust discount).
 */
public enum TrustRiskLevel {
  LOW,
  MEDIUM,
  HIGH,
  CRITICAL;

  public boolean atLeast(TrustRiskLevel other) {
    return this.ordinal() >= other.ordinal();
  }

  public TrustRiskLevel max(TrustRiskLevel other) {
    return this.ordinal() >= other.ordinal() ? this : other;
  }
}
