package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.security.policy.ActorRole;
import com.orderpilot.security.policy.TenantPolicyException;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

class RequestActorRoleResolverTest {
  private final RequestActorRoleResolver resolver = new RequestActorRoleResolver();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void quoteActionMapsToSalesRoleWithoutClientRoleInput() {
    authenticate(ApiPermission.QUOTE_ACTION);

    assertThat(resolver.resolveQuoteRole()).isEqualTo(ActorRole.SALES_QUOTE_MANAGER);
  }

  @Test
  void quoteActionPlusAdminManageMapsToOwnerRole() {
    authenticate(ApiPermission.QUOTE_ACTION, ApiPermission.ADMIN_SETTINGS_MANAGE);

    assertThat(resolver.resolveQuoteRole()).isEqualTo(ActorRole.OWNER_ADMIN);
  }

  @Test
  void tenantOperatorAuthorityCannotManufactureQuoteApprovalRole() {
    authenticate(ApiPermission.REVIEW_ACTION);

    assertThatThrownBy(resolver::resolveQuoteRole)
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("quote action");
  }

  private static void authenticate(ApiPermission... permissions) {
    var authorities = Arrays.stream(permissions)
        .map(permission -> new SimpleGrantedAuthority("ORDERPILOT_" + permission.name()))
        .toList();
    SecurityContextHolder.getContext().setAuthentication(
        new PreAuthenticatedAuthenticationToken("trusted-gateway", "N/A", authorities));
  }
}
