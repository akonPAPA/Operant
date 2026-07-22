package com.orderpilot.security;

/**
 * Immutable authenticated identity for a dedicated control-plane principal.
 *
 * <p>The stable {@code principalId} is server-owned and survives credential-key rotation. The credential
 * alias and key version identify the concrete authentication credential used for the request, while
 * {@code principalType} defines the authorization plane. No key material, signature, tenant, browser
 * actor, or client-supplied permission state is carried here.
 */
public record ControlPlanePrincipal(
    String principalId,
    String credentialAlias,
    String keyVersion,
    ControlPlanePrincipalType principalType) {
  public ControlPlanePrincipal {
    if (principalId == null || principalId.isBlank()
        || credentialAlias == null || credentialAlias.isBlank()
        || keyVersion == null || keyVersion.isBlank()
        || principalType == null) {
      throw new IllegalArgumentException("control principal attribution is incomplete");
    }
  }

  @Override
  public String toString() {
    return "ControlPlanePrincipal{credentialAlias="
        + credentialAlias
        + ", keyVersion="
        + keyVersion
        + ", principalType="
        + principalType
        + ", principalFingerprint="
        + ControlPlanePrincipalFingerprint.of(this)
        + "}";
  }
}
