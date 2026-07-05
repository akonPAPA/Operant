package com.orderpilot.application.services.support;

import com.orderpilot.domain.support.StaffRole;
import java.util.UUID;

/**
 * PR #247 (Staff Identity Resolver Seam) — the minimal, backend-owned staff principal produced by a
 * {@link StaffIdentityResolver}. It carries only what internal-support authorization needs and is NEVER
 * constructed from a client request body or query parameter.
 *
 * <p>It intentionally exposes no secret/credential, no tenant authority, and no support-grant id. The
 * {@code staffUserId} is the trusted, backend-resolved staff subject; it is not accepted from the client.
 */
public record ResolvedStaffPrincipal(UUID staffUserId, StaffRole role, String handle, Source source) {

  /**
   * Where the staff identity was established. Today the only source is the trusted gateway actor header
   * ({@link TrustedGatewayStaffIdentityResolver}). Future OIDC/BFF/SSO sources would add values here
   * without changing support authorization semantics.
   */
  public enum Source {
    TRUSTED_GATEWAY_HEADER
  }
}
