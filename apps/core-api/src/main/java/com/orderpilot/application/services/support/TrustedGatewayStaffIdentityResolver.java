package com.orderpilot.application.services.support;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.support.StaffUser;
import com.orderpilot.domain.support.StaffUserRepository;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * PR #247 — the first {@link StaffIdentityResolver} implementation. It WRAPS the existing trusted
 * gateway/header actor resolution ({@link RequestActorResolver#resolveVerifiedActor}) and then validates
 * the resolved actor against the {@code staff_user} registry.
 *
 * <p>It introduces no new trust source: the acting actor is still the signed/verified trusted gateway
 * actor header (never a request body or query field), bound to the current tenant context exactly as
 * before. The only thing this seam adds over the previous inline path is that it fails closed at the
 * identity boundary when the trusted actor is the unauthenticated fallback sentinel, is unknown, or maps
 * to a disabled staff user — so a tenant/operator/service actor can never be treated as staff.
 *
 * <p>This is deliberately NOT an SSO/OIDC/BFF implementation. It is the seam that lets one be swapped in
 * later without touching {@link SupportAccessService} authorization.
 */
@Component
public class TrustedGatewayStaffIdentityResolver implements StaffIdentityResolver {

  private final RequestActorResolver actorResolver;
  private final StaffUserRepository staffUserRepository;

  public TrustedGatewayStaffIdentityResolver(
      RequestActorResolver actorResolver, StaffUserRepository staffUserRepository) {
    this.actorResolver = actorResolver;
    this.staffUserRepository = staffUserRepository;
  }

  @Override
  public ResolvedStaffPrincipal resolveRequired(HttpServletRequest request) {
    // Same trusted-gateway actor path as before: the signed/verified actor header bound to the current
    // tenant context. A malformed actor header (400) or signed-mode missing actor (401) propagates
    // unchanged. The tenant binding is read from the trusted context exactly as the controller passed it
    // inline previously (the scoped-tenant context for tenant routes; empty for the cross-tenant locator).
    UUID tenantId = TenantContext.getTenantId().orElse(null);
    UUID actorId = actorResolver.resolveVerifiedActor(request, tenantId);

    // No unauthenticated fallback for staff: the headerless SYSTEM sentinel is not a staff identity.
    if (actorId == null || RequestActorResolver.SYSTEM_ACTOR.equals(actorId)) {
      throw new SupportAccessDeniedException("Support access denied");
    }

    // A staff identity exists only when the trusted actor maps to an ACTIVE staff_user row. A tenant
    // user/admin or a machine/service-account actor never has such a row, so it can never become staff.
    StaffUser staff = staffUserRepository.findById(actorId).orElse(null);
    if (staff == null || !staff.isActive()) {
      throw new SupportAccessDeniedException("Support access denied");
    }

    return new ResolvedStaffPrincipal(
        staff.getId(),
        staff.getRole(),
        staff.getHandle(),
        ResolvedStaffPrincipal.Source.TRUSTED_GATEWAY_HEADER);
  }
}
