package com.orderpilot.application.services.support;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.domain.support.StaffSupportScope;
import com.orderpilot.domain.support.StaffUser;
import com.orderpilot.domain.support.StaffUserRepository;
import com.orderpilot.domain.support.SupportAccessGrant;
import com.orderpilot.domain.support.SupportAccessGrantRepository;
import com.orderpilot.domain.tenant.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupportTenantLocatorBoundaryTest {
  private static final Instant NOW = Instant.parse("2026-06-30T12:00:00Z");

  @Test
  void tenantLookupReceivesOnlyGrantDerivedEligibleTenantIds() {
    StaffUserRepository staffRepository = mock(StaffUserRepository.class);
    SupportAccessGrantRepository grantRepository = mock(SupportAccessGrantRepository.class);
    TenantRepository tenantRepository = mock(TenantRepository.class);
    AuditEventService auditEventService = mock(AuditEventService.class);
    StaffUser staff = mock(StaffUser.class);
    SupportAccessGrant grant = mock(SupportAccessGrant.class);
    UUID staffId = UUID.randomUUID();
    UUID grantedTenantId = UUID.randomUUID();

    when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
    when(staff.isActive()).thenReturn(true);
    when(staff.permits(StaffSupportScope.DIAGNOSTICS)).thenReturn(true);
    when(grant.getTenantId()).thenReturn(grantedTenantId);
    when(grant.getScope()).thenReturn(StaffSupportScope.DIAGNOSTICS);
    when(grant.isUsable(NOW)).thenReturn(true);
    when(grantRepository.findByStaffUserIdAndStatusAndApprovalStatusInAndExpiresAtAfter(
        staffId,
        SupportAccessGrant.Status.ACTIVE,
        List.of(
            SupportAccessGrant.ApprovalStatus.AUTO_APPROVED,
            SupportAccessGrant.ApprovalStatus.APPROVED),
        NOW)).thenReturn(List.of(grant));
    when(tenantRepository.findAllById(org.mockito.ArgumentMatchers.<Iterable<UUID>>any()))
        .thenReturn(List.of());

    SupportTenantLocatorService service = new SupportTenantLocatorService(
        staffRepository,
        grantRepository,
        tenantRepository,
        auditEventService,
        new ObjectMapper(),
        Clock.fixed(NOW, ZoneOffset.UTC));

    service.search(staffId, "", 0, 20);

    verify(tenantRepository).findAllById(Set.of(grantedTenantId));
  }
}
