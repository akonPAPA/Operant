package com.orderpilot.security;

/**
 * Immutable authenticated identity for the dedicated Operant support/control credential plane.
 * Contains server-owned attribution only; it never carries key material, signatures, tenant, actor,
 * or client-supplied permission state.
 */
public record ControlPlanePrincipal(
    String credentialAlias,
    String keyVersion,
    String principalType) {
  public ControlPlanePrincipal {
    if (credentialAlias == null || credentialAlias.isBlank()
        || keyVersion == null || keyVersion.isBlank()
        || principalType == null || principalType.isBlank()) {
      throw new IllegalArgumentException("control principal attribution is incomplete");
    }
  }
}
