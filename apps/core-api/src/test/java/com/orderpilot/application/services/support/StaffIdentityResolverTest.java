package com.orderpilot.application.services.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.support.StaffRole;
import com.orderpilot.domain.support.StaffUser;
import com.orderpilot.domain.support.StaffUserRepository;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * PR #247 — unit proof for {@link TrustedGatewayStaffIdentityResolver}: the seam wraps the trusted
 * gateway actor path and fails closed for every non-staff identity. It never reads staff identity from a
 * body/query (it only forwards the request to the trusted {@link RequestActorResolver}).
 */
class StaffIdentityResolverTest {
  private final RequestActorResolver actorResolver = mock(RequestActorResolver.class);
  private final StaffUserRepository staffUserRepository = mock(StaffUserRepository.class);
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final TrustedGatewayStaffIdentityResolver resolver =
      new TrustedGatewayStaffIdentityResolver(actorResolver, staffUserRepository);

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void missingStaffIdentityFailsClosed() {
    // Unsigned/local fallback with no actor header resolves to the SYSTEM sentinel — not a staff identity.
    when(actorResolver.resolveVerifiedActor(any(), any())).thenReturn(RequestActorResolver.SYSTEM_ACTOR);

    assertThatThrownBy(() -> resolver.resolveRequired(request))
        .isInstanceOf(SupportAccessDeniedException.class);
  }

  @Test
  void malformedStaffIdentityFailsClosed() {
    // A malformed trusted actor header surfaces as the existing 400-class error and is NOT swallowed.
    when(actorResolver.resolveVerifiedActor(any(), any()))
        .thenThrow(new IllegalArgumentException("Invalid X-OrderPilot-Actor-Id header"));

    assertThatThrownBy(() -> resolver.resolveRequired(request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownStaffIdentityFailsClosed() {
    UUID actor = UUID.randomUUID();
    when(actorResolver.resolveVerifiedActor(any(), any())).thenReturn(actor);
    when(staffUserRepository.findById(actor)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.resolveRequired(request))
        .isInstanceOf(SupportAccessDeniedException.class);
  }

  @Test
  void disabledStaffUserFailsClosed() {
    UUID actor = UUID.randomUUID();
    StaffUser disabled = mock(StaffUser.class);
    when(disabled.isActive()).thenReturn(false);
    when(actorResolver.resolveVerifiedActor(any(), any())).thenReturn(actor);
    when(staffUserRepository.findById(actor)).thenReturn(Optional.of(disabled));

    assertThatThrownBy(() -> resolver.resolveRequired(request))
        .isInstanceOf(SupportAccessDeniedException.class);
  }

  @Test
  void tenantOperatorActorAloneIsNotStaff() {
    // A tenant operator carries a trusted actor id but has no staff_user row -> not staff.
    UUID tenantOperator = UUID.randomUUID();
    when(actorResolver.resolveVerifiedActor(any(), any())).thenReturn(tenantOperator);
    when(staffUserRepository.findById(tenantOperator)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.resolveRequired(request))
        .isInstanceOf(SupportAccessDeniedException.class);
  }

  @Test
  void tenantAdminActorIsNotStaff() {
    UUID tenantAdmin = UUID.randomUUID();
    when(actorResolver.resolveVerifiedActor(any(), any())).thenReturn(tenantAdmin);
    when(staffUserRepository.findById(tenantAdmin)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.resolveRequired(request))
        .isInstanceOf(SupportAccessDeniedException.class);
  }

  @Test
  void serviceAccountActorIsNotStaff() {
    // A machine/service actor id likewise has no staff_user row -> not staff.
    UUID serviceAccount = UUID.randomUUID();
    when(actorResolver.resolveVerifiedActor(any(), any())).thenReturn(serviceAccount);
    when(staffUserRepository.findById(serviceAccount)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.resolveRequired(request))
        .isInstanceOf(SupportAccessDeniedException.class);
  }

  @Test
  void validStaffIdentityResolvesToExpectedPrincipal() {
    UUID staffId = UUID.randomUUID();
    StaffUser staff = mock(StaffUser.class);
    when(staff.isActive()).thenReturn(true);
    when(staff.getId()).thenReturn(staffId);
    when(staff.getRole()).thenReturn(StaffRole.SUPPORT_ENGINEER);
    when(staff.getHandle()).thenReturn("sre@operant");
    when(actorResolver.resolveVerifiedActor(any(), any())).thenReturn(staffId);
    when(staffUserRepository.findById(staffId)).thenReturn(Optional.of(staff));

    ResolvedStaffPrincipal principal = resolver.resolveRequired(request);

    assertThat(principal.staffUserId()).isEqualTo(staffId);
    assertThat(principal.role()).isEqualTo(StaffRole.SUPPORT_ENGINEER);
    assertThat(principal.handle()).isEqualTo("sre@operant");
    assertThat(principal.source()).isEqualTo(ResolvedStaffPrincipal.Source.TRUSTED_GATEWAY_HEADER);
  }
}
