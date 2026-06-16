package com.orderpilot.domain.trust;

/**
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * Severity of an individual deterministic trust signal. INFO is non-material (does not raise risk
 * on its own); WARNING/HIGH/CRITICAL are material. Ordering matters: higher ordinal = more severe.
 */
public enum TrustSignalSeverity {
  INFO,
  WARNING,
  HIGH,
  CRITICAL;

  public boolean isMaterial() {
    return this.ordinal() >= WARNING.ordinal();
  }
}
