package com.orderpilot.domain.incident;

/**
 * OP-CAP-53 — bounded incident severity ladder. {@link #CRITICAL} is the strongest: a CRITICAL incident
 * raises a record-only alert on creation and can never be silently closed without a closure reason.
 */
public enum IncidentSeverity {
  LOW,
  MEDIUM,
  HIGH,
  CRITICAL;

  public boolean isCritical() {
    return this == CRITICAL;
  }
}
