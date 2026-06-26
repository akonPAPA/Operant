package com.orderpilot.domain.incident;

/**
 * OP-CAP-53 — incident lifecycle status. An incident is {@code OPEN} until it is explicitly closed with a
 * closure reason. A {@code CLOSED} incident is terminal: it can never receive a new approved break-glass
 * grant, so emergency access cannot silently outlive the incident that justified it.
 */
public enum IncidentStatus {
  OPEN,
  CLOSED;

  public boolean isClosed() {
    return this == CLOSED;
  }
}
