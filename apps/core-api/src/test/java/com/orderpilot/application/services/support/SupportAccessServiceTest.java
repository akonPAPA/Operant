package com.orderpilot.application.services.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.support.StaffRole;
import com.orderpilot.domain.support.StaffSupportScope;
import com.orderpilot.domain.support.StaffUser;
import com.orderpilot.domain.support.StaffUserRepository;
import com.orderpilot.domain.support.SupportAccessGrant;
import com.orderpilot.domain.support.SupportAccessGrantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-51 — proves the support access decision is fail-closed and audited. No grant, expired grant,
 * wrong tenant, wrong scope, and an unknown/role-insufficient principal are all denied; a valid grant plus
 * a permitting role is allowed. Every decision (allow and deny) emits an audit event.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SupportAccessServiceTest {
  private static final Instant T0 = Instant.parse("2026-06-26T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

  @Autowired private StaffUserRepository staffUserRepository;
  @Autowired private SupportAccessGrantRepository grantRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  private SupportAccessService service;
  private UUID tenantId;

  @BeforeEach
  void setUp() {
    AuditEventService auditEventService = new AuditEventService(auditEventRepository, CLOCK);
    service = new SupportAccessService(
        staffUserRepository, grantRepository, auditEventService, new ObjectMapper(), CLOCK);
    tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private StaffUser activeStaff(StaffRole role) {
    return staffUserRepository.save(new StaffUser("support-" + UUID.randomUUID(), role, T0));
  }

  private SupportAccessGrant grant(UUID staffUserId, UUID grantTenantId, StaffSupportScope scope, Instant expiresAt) {
    return grantRepository.save(new SupportAccessGrant(
        staffUserId, grantTenantId, scope, "CASE-123", expiresAt, UUID.randomUUID(), T0));
  }

  private long countAudits(String action) {
    return auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction())).count();
  }

  @Test
  void noGrantIsDeniedAndAudited() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);

    assertThatThrownBy(() -> service.authorize(staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS))
        .isInstanceOf(SupportAccessDeniedException.class);

    assertThat(countAudits("SUPPORT_ACCESS_DENIED")).isEqualTo(1);
    assertThat(countAudits("SUPPORT_ACCESS_GRANTED")).isZero();
  }

  @Test
  void expiredGrantIsDenied() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    grant(staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS, T0.minus(Duration.ofHours(1)));

    assertThatThrownBy(() -> service.authorize(staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS))
        .isInstanceOf(SupportAccessDeniedException.class);
    assertThat(countAudits("SUPPORT_ACCESS_DENIED")).isEqualTo(1);
  }

  @Test
  void wrongTenantGrantIsDenied() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    UUID otherTenant = UUID.randomUUID();
    grant(staff.getId(), otherTenant, StaffSupportScope.DIAGNOSTICS, T0.plus(Duration.ofHours(1)));

    assertThatThrownBy(() -> service.authorize(staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS))
        .isInstanceOf(SupportAccessDeniedException.class);
  }

  @Test
  void wrongScopeGrantIsDenied() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    // A grant for DIAGNOSTICS must not satisfy a DATA_REPAIR authorization.
    grant(staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS, T0.plus(Duration.ofHours(1)));

    assertThatThrownBy(() -> service.authorize(staff.getId(), tenantId, StaffSupportScope.DATA_REPAIR))
        .isInstanceOf(SupportAccessDeniedException.class);
  }

  @Test
  void roleThatDoesNotPermitScopeIsDeniedEvenWithAGrantRow() {
    // A SUPPORT_VIEWER may never hold DATA_REPAIR — even a (mis-issued) grant row cannot elevate the role.
    StaffUser viewer = activeStaff(StaffRole.SUPPORT_VIEWER);
    grant(viewer.getId(), tenantId, StaffSupportScope.DATA_REPAIR, T0.plus(Duration.ofHours(1)));

    assertThatThrownBy(() -> service.authorize(viewer.getId(), tenantId, StaffSupportScope.DATA_REPAIR))
        .isInstanceOf(SupportAccessDeniedException.class);
  }

  @Test
  void unknownPrincipalIsDenied() {
    assertThatThrownBy(() -> service.authorize(UUID.randomUUID(), tenantId, StaffSupportScope.DIAGNOSTICS))
        .isInstanceOf(SupportAccessDeniedException.class);
    assertThat(countAudits("SUPPORT_ACCESS_DENIED")).isEqualTo(1);
  }

  @Test
  void validGrantWithPermittingRoleIsAllowedAndAudited() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    SupportAccessGrant g = grant(staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS, T0.plus(Duration.ofHours(1)));

    SupportAccessService.SupportSession session =
        service.authorize(staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS);

    assertThat(session.staffUserId()).isEqualTo(staff.getId());
    assertThat(session.tenantId()).isEqualTo(tenantId);
    assertThat(session.scope()).isEqualTo(StaffSupportScope.DIAGNOSTICS);
    assertThat(session.grantId()).isEqualTo(g.getId());
    assertThat(countAudits("SUPPORT_ACCESS_GRANTED")).isEqualTo(1);
    assertThat(countAudits("SUPPORT_ACCESS_DENIED")).isZero();
  }

  @Test
  void createGrantRequiresSupportCaseReferenceAndBoundedTtl() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);

    assertThatThrownBy(() -> service.createGrant(
        staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS, "  ", Duration.ofHours(1), UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> service.createGrant(
        staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS, "CASE-1",
        SupportAccessService.MAX_GRANT_TTL.plusSeconds(1), UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createGrantPersistsExpiringActiveGrantAndAudits() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);

    SupportAccessGrant g = service.createGrant(
        staff.getId(), tenantId, StaffSupportScope.MAINTENANCE, "CASE-9", Duration.ofHours(2), UUID.randomUUID());

    assertThat(g.getStatus()).isEqualTo(SupportAccessGrant.Status.ACTIVE);
    assertThat(g.getExpiresAt()).isEqualTo(T0.plus(Duration.ofHours(2)));
    assertThat(g.getTenantId()).isEqualTo(tenantId);
    assertThat(countAudits("SUPPORT_ACCESS_GRANT_CREATED")).isEqualTo(1);
  }

  @Test
  void revokedGrantIsNoLongerUsable() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    SupportAccessGrant g = grant(staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS, T0.plus(Duration.ofHours(1)));

    service.revokeGrant(g.getId(), tenantId, UUID.randomUUID());

    assertThatThrownBy(() -> service.authorize(staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS))
        .isInstanceOf(SupportAccessDeniedException.class);
    assertThat(countAudits("SUPPORT_ACCESS_GRANT_REVOKED")).isEqualTo(1);
  }
}
