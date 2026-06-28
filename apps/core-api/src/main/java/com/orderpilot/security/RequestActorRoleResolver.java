package com.orderpilot.security;

import com.orderpilot.security.policy.ActorRole;
import com.orderpilot.security.policy.TenantPolicyException;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves policy roles from the already-authenticated runtime authority set.
 *
 * <p>The gateway authentication filter creates these authorities only after validating the signed
 * tenant/actor/permission envelope. This resolver never reads a role from a request body or an
 * unsigned role header.
 */
@Component
public class RequestActorRoleResolver {
  private static final String PREFIX = "ORDERPILOT_";

  public ActorRole resolveQuoteRole() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new TenantPolicyException("Authenticated quote role is required");
    }
    Set<String> authorities = authentication.getAuthorities().stream()
        .map(authority -> authority.getAuthority())
        .collect(Collectors.toSet());
    if (!authorities.contains(PREFIX + ApiPermission.QUOTE_ACTION.name())) {
      throw new TenantPolicyException("Missing authenticated quote action authority");
    }
    if (authorities.contains(PREFIX + ApiPermission.ADMIN_SETTINGS_MANAGE.name())) {
      return ActorRole.OWNER_ADMIN;
    }
    return ActorRole.SALES_QUOTE_MANAGER;
  }
}
