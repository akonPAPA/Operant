package com.orderpilot.security;

import com.orderpilot.security.policy.TenantPolicyException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class ApiPermissionGuard {
  public static final String PERMISSIONS_HEADER = "X-OrderPilot-Permissions";

  public void require(HttpServletRequest request, ApiPermission permission) {
    if (hasAuthenticatedAuthority(permission)) {
      return;
    }
    String header = request.getHeader(PERMISSIONS_HEADER);
    if (header == null || header.isBlank()) {
      throw new TenantPolicyException("Missing required API permission " + permission.name());
    }
    Set<String> granted = Arrays.stream(header.split(","))
        .map(value -> value.trim().toUpperCase(Locale.ROOT))
        .filter(value -> !value.isBlank())
        .collect(Collectors.toSet());
    if (!granted.contains(permission.name())) {
      throw new TenantPolicyException("Missing required API permission " + permission.name());
    }
  }

  private boolean hasAuthenticatedAuthority(ApiPermission permission) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }
    String required = "ORDERPILOT_" + permission.name();
    return authentication.getAuthorities().stream()
        .anyMatch(authority -> required.equals(authority.getAuthority()));
  }
}
