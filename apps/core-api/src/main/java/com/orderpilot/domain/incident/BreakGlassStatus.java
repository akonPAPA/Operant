package com.orderpilot.domain.incident;

/**
 * OP-CAP-53 — break-glass access request lifecycle. A request is {@code REQUESTED} until a separate approver
 * decides it. Only an {@code APPROVED} request that has not expired/been revoked is ever usable; every other
 * state ({@code REJECTED}, {@code REVOKED}, {@code EXPIRED}) is permanently unusable. There is no state in
 * which a request silently grants standing privileged access — emergency access always expires.
 */
public enum BreakGlassStatus {
  REQUESTED,
  APPROVED,
  REJECTED,
  REVOKED,
  EXPIRED
}
