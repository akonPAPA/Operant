package com.orderpilot.application.services.support;

import jakarta.servlet.http.HttpServletRequest;

/**
 * PR #247 — Staff Identity Resolver Seam.
 *
 * <p>A thin abstraction over "who is the acting Operant staff principal for this internal-support
 * request." It exists so the internal support plane depends on a dedicated staff-identity contract
 * rather than directly on the generic tenant/operator actor-resolution logic, and so the backing
 * identity source can later be swapped (trusted gateway header → OIDC/BFF/SSO) WITHOUT changing support
 * authorization semantics in {@link SupportAccessService} or
 * {@link com.orderpilot.api.rest.InternalSupportController}.
 *
 * <p>Contract (fail-closed):
 * <ul>
 *   <li>the acting staff identity is taken only from the trusted request context — never from a request
 *       body or query parameter, and never proposed by the frontend as authority;</li>
 *   <li>a missing, malformed, unauthenticated-fallback, unknown, or disabled staff identity fails closed
 *       (a {@link SupportAccessDeniedException}, or the existing malformed/verification errors for a
 *       malformed/absent trusted actor);</li>
 *   <li>a tenant user/admin actor or a machine/service-account actor is NOT a staff identity — it
 *       resolves only when it maps to an ACTIVE {@code StaffUser} row.</li>
 * </ul>
 */
public interface StaffIdentityResolver {

  /**
   * Resolve the acting staff principal for the current internal-support request, or fail closed.
   *
   * @param request the current HTTP request (used only for trusted request-context headers)
   * @return the resolved, ACTIVE staff principal
   * @throws SupportAccessDeniedException if no ACTIVE staff identity can be established
   */
  ResolvedStaffPrincipal resolveRequired(HttpServletRequest request);
}
