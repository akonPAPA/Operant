package com.orderpilot.application.services.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportInternalDtos.MaintenanceActionRecordRequest;
import com.orderpilot.api.dto.SupportInternalDtos.MaintenanceActionRecordResponse;
import com.orderpilot.api.dto.SupportInternalDtos.SupportTenantDiagnosticsResponse;
import com.orderpilot.api.rest.InternalSupportController;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.support.StaffRole;
import com.orderpilot.domain.support.StaffSupportScope;
import com.orderpilot.domain.support.StaffUser;
import com.orderpilot.domain.support.StaffUserRepository;
import com.orderpilot.domain.support.SupportAccessGrant;
import com.orderpilot.domain.support.SupportAccessGrantRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * PR proof/support-grant-lifecycle-matrix — the single readable authorization matrix for the internal
 * owner-company support plane. It proves that support/internal routes do not merely require a valid staff
 * identity (the PR #247 {@link StaffIdentityResolver} seam) but ALSO enforce the correct
 * {@link SupportAccessGrant} lifecycle and scope: no grant, expired grant, wrong-tenant grant, wrong-scope
 * grant, and a read-only grant used for a mutation are all denied; a tenant admin / average tenant user /
 * service account never becomes a staff support actor; and only a valid staff principal with a matching,
 * usable grant is allowed — a mutation only ever with an explicit (approved) mutation-scope grant.
 *
 * <p>This is a defense-in-depth proof that runs the REAL {@link SupportAccessService} (real grant lifecycle,
 * real audit) behind the REAL {@link InternalSupportController} entry points. The route-edge permission
 * layer ({@code STAFF_*} mapping, tenant-permission rejection, 401/403) is proven separately by
 * {@code SupportAccessRoutePermissionTest}, {@code InternalSupportControllerSecurityTest} and
 * {@code InternalSupportVisibilityBoundaryTest}; the identity seam itself by {@code StaffIdentityResolverTest}.
 * Here the only mocks are the leaf read/mutation collaborators (so "was the side effect invoked?" can be
 * asserted) and the identity seam (so a non-staff actor can be represented without a real gateway signer).
 *
 * <p>Matrix (each row is one required case):
 * <pre>
 *   #  scenario                                            expected
 *   1  valid staff, no grant                               DENY  (no diagnostics read)
 *   2  valid staff, expired grant                          DENY
 *   3  valid staff, grant for a different tenant           DENY
 *   4  valid staff, grant for a different scope            DENY
 *   5  valid staff, read-only grant used on a mutation     DENY  (no maintenance write)
 *   6  tenant admin actor (no staff identity)              DENY
 *   7  average tenant user actor (no staff identity)       DENY
 *   8  service account actor (no staff identity)           DENY
 *   9  valid staff, matching usable read grant             ALLOW read
 *   10 valid staff, explicit approved mutation grant       ALLOW that mutation only
 *   11 denied request                                      no service call / no allow side effect
 *   12 allowed privileged action                           emits SUPPORT_ACCESS_GRANTED audit
 * </pre>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SupportGrantLifecycleMatrixTest {
  private static final Instant T0 = Instant.parse("2026-07-06T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

  @Autowired private StaffUserRepository staffUserRepository;
  @Autowired private SupportAccessGrantRepository grantRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  private SupportAccessService supportAccessService;
  private InternalSupportController controller;

  // Leaf collaborators are mocked so a denied request can be proven to make NO downstream call, and an
  // allowed request can be proven to make exactly the one expected call.
  private SupportDiagnosticsService diagnosticsService;
  private MaintenanceActionService maintenanceActionService;
  private StaffIdentityResolver staffIdentityResolver;
  private final HttpServletRequest http = mock(HttpServletRequest.class);

  private UUID tenantId;

  @BeforeEach
  void setUp() {
    AuditEventService auditEventService = new AuditEventService(auditEventRepository, CLOCK);
    supportAccessService = new SupportAccessService(
        staffUserRepository, grantRepository, auditEventService, new ObjectMapper(), CLOCK);

    diagnosticsService = mock(SupportDiagnosticsService.class);
    maintenanceActionService = mock(MaintenanceActionService.class);
    staffIdentityResolver = mock(StaffIdentityResolver.class);

    controller = new InternalSupportController(
        supportAccessService,
        diagnosticsService,
        maintenanceActionService,
        mock(DataRepairService.class),
        mock(ProcessingJobRepairExecutor.class),
        mock(SupportOperationsService.class),
        mock(SupportTenantLocatorService.class),
        staffIdentityResolver);

    tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // --- fixtures -------------------------------------------------------------------------------------

  private StaffUser activeStaff(StaffRole role) {
    return staffUserRepository.save(new StaffUser("support-" + UUID.randomUUID(), role, T0));
  }

  /** Wire the identity seam to resolve the current request to this ACTIVE staff principal. */
  private void resolvesToStaff(StaffUser staff) {
    when(staffIdentityResolver.resolveRequired(any())).thenReturn(new ResolvedStaffPrincipal(
        staff.getId(), staff.getRole(), staff.getHandle(),
        ResolvedStaffPrincipal.Source.TRUSTED_GATEWAY_HEADER));
  }

  /**
   * Wire the identity seam to FAIL CLOSED, as it does for any actor that is not an ACTIVE staff user (a
   * tenant admin, an average tenant user, or a machine/service account carry no staff_user row).
   */
  private void resolvesToNoStaffIdentity() {
    when(staffIdentityResolver.resolveRequired(any()))
        .thenThrow(new SupportAccessDeniedException("Support access denied"));
  }

  private SupportAccessGrant persistGrant(
      UUID staffUserId, UUID grantTenantId, StaffSupportScope scope, Instant expiresAt) {
    return grantRepository.save(new SupportAccessGrant(
        staffUserId, grantTenantId, scope, "CASE-MATRIX", expiresAt, UUID.randomUUID(), T0));
  }

  /** A usable read-only DIAGNOSTICS grant (auto-approved low-risk, unexpired). */
  private void usableDiagnosticsGrant(UUID staffUserId) {
    persistGrant(staffUserId, tenantId, StaffSupportScope.DIAGNOSTICS, T0.plus(Duration.ofHours(1)));
  }

  /** An explicit, approved, usable MAINTENANCE (mutation) grant. */
  private void approvedMaintenanceGrant(UUID staffUserId) {
    SupportAccessGrant grant = new SupportAccessGrant(
        staffUserId, tenantId, StaffSupportScope.MAINTENANCE, "CASE-MUTATE",
        T0.plus(Duration.ofHours(1)), UUID.randomUUID(), T0);
    grant.approve(UUID.randomUUID(), "approved for matrix proof", T0);
    grantRepository.save(grant);
  }

  private MaintenanceActionRecordRequest maintenanceRequest() {
    return new MaintenanceActionRecordRequest("RUNTIME_DIAGNOSTIC", "matrix-proof", "runtime");
  }

  private long countAudits(String action) {
    return auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction())).count();
  }

  // --- 1. no grant -> deny --------------------------------------------------------------------------

  @Test
  void deniesWhenNoGrantExists() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    resolvesToStaff(staff);

    assertThatThrownBy(() -> controller.diagnostics(tenantId, http))
        .isInstanceOf(SupportAccessDeniedException.class);

    verifyNoInteractions(diagnosticsService);
    assertThat(countAudits("SUPPORT_ACCESS_DENIED")).isEqualTo(1);
    assertThat(countAudits("SUPPORT_ACCESS_GRANTED")).isZero();
  }

  // --- 2. expired grant -> deny ---------------------------------------------------------------------

  @Test
  void deniesWhenGrantExpired() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    resolvesToStaff(staff);
    persistGrant(staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS, T0.minus(Duration.ofMinutes(1)));

    assertThatThrownBy(() -> controller.diagnostics(tenantId, http))
        .isInstanceOf(SupportAccessDeniedException.class);
    verifyNoInteractions(diagnosticsService);
  }

  // --- 3. wrong-tenant grant -> deny ----------------------------------------------------------------

  @Test
  void deniesWhenGrantTenantDoesNotMatchRequestTenant() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    resolvesToStaff(staff);
    // A usable grant, but minted for a DIFFERENT tenant — it can never satisfy this tenant's request.
    persistGrant(staff.getId(), UUID.randomUUID(), StaffSupportScope.DIAGNOSTICS, T0.plus(Duration.ofHours(1)));

    assertThatThrownBy(() -> controller.diagnostics(tenantId, http))
        .isInstanceOf(SupportAccessDeniedException.class);
    verifyNoInteractions(diagnosticsService);
  }

  // --- 4. wrong-scope grant -> deny -----------------------------------------------------------------

  @Test
  void deniesWhenGrantScopeDoesNotCoverRequestedAction() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    resolvesToStaff(staff);
    // A usable DIAGNOSTICS grant does not cover a MAINTENANCE (mutation) action.
    usableDiagnosticsGrant(staff.getId());

    assertThatThrownBy(() -> controller.recordMaintenance(tenantId, maintenanceRequest(), http))
        .isInstanceOf(SupportAccessDeniedException.class);
    verifyNoInteractions(maintenanceActionService);
  }

  // --- 5. read-only grant cannot mutate (and the mutation service is never reached) -----------------

  @Test
  void deniesReadonlyGrantOnMutationAndDoesNotInvokeMutationService() {
    // A SUPPORT_VIEWER is a read-only staff role; even with a usable read grant it can never mutate, and
    // the maintenance mutation service must never be invoked on the denied path.
    StaffUser viewer = activeStaff(StaffRole.SUPPORT_VIEWER);
    resolvesToStaff(viewer);
    usableDiagnosticsGrant(viewer.getId());

    assertThatThrownBy(() -> controller.recordMaintenance(tenantId, maintenanceRequest(), http))
        .isInstanceOf(SupportAccessDeniedException.class);

    verify(maintenanceActionService, never()).record(any(), any(), any(), any(), any());
    verifyNoInteractions(maintenanceActionService);
    assertThat(countAudits("SUPPORT_ACCESS_DENIED")).isEqualTo(1);
  }

  // --- 6. tenant admin -> deny ----------------------------------------------------------------------

  @Test
  void deniesTenantAdminEvenWithTenantAdminPermission() {
    // A tenant admin carries a tenant-admin permission header (rejected at the route edge by
    // SupportAccessRoutePermissionTest) AND, even if it reached the controller, resolves to no staff
    // identity through the seam — so it can never become an Operant support actor.
    resolvesToNoStaffIdentity();

    assertThatThrownBy(() -> controller.diagnostics(tenantId, http))
        .isInstanceOf(SupportAccessDeniedException.class);

    verifyNoInteractions(diagnosticsService);
    // Identity is denied BEFORE the grant/authorize layer, so no support-access audit is emitted here.
    assertThat(countAudits("SUPPORT_ACCESS_GRANTED")).isZero();
  }

  // --- 7. average tenant user -> deny ---------------------------------------------------------------

  @Test
  void deniesAverageTenantUser() {
    // An ordinary tenant operator/user is never a staff support actor.
    resolvesToNoStaffIdentity();

    assertThatThrownBy(() -> controller.diagnostics(tenantId, http))
        .isInstanceOf(SupportAccessDeniedException.class);
    verifyNoInteractions(diagnosticsService);
  }

  // --- 8. service account -> deny -------------------------------------------------------------------

  @Test
  void deniesServiceAccount() {
    // A machine/service-account actor (including the unauthenticated SYSTEM sentinel) has no staff_user
    // row and must never be elevated to a staff support actor.
    resolvesToNoStaffIdentity();

    assertThatThrownBy(() -> controller.recordMaintenance(tenantId, maintenanceRequest(), http))
        .isInstanceOf(SupportAccessDeniedException.class);
    verifyNoInteractions(maintenanceActionService);
  }

  // --- 9. valid staff + valid read grant -> allow read ----------------------------------------------

  @Test
  void allowsValidStaffReadWithMatchingGrant() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_VIEWER);
    resolvesToStaff(staff);
    usableDiagnosticsGrant(staff.getId());
    SupportTenantDiagnosticsResponse safe = new SupportTenantDiagnosticsResponse(
        tenantId, "HEALTHY", Map.of("COMPLETED", 3L), 3,
        T0.minusSeconds(60), T0, "DISABLED", "TENANT_SAFE_DIAGNOSTICS");
    when(diagnosticsService.diagnose(tenantId)).thenReturn(safe);

    SupportTenantDiagnosticsResponse response = controller.diagnostics(tenantId, http);

    assertThat(response.health()).isEqualTo("HEALTHY");
    assertThat(response.externalExecution()).isEqualTo("DISABLED");
    verify(diagnosticsService).diagnose(tenantId);
    assertThat(countAudits("SUPPORT_ACCESS_GRANTED")).isEqualTo(1);
    assertThat(countAudits("SUPPORT_ACCESS_DENIED")).isZero();
  }

  // --- 10. valid staff + explicit mutation grant -> allow only that mutation ------------------------

  @Test
  void allowsValidStaffMutationOnlyWithExplicitMutationGrant() {
    StaffUser engineer = activeStaff(StaffRole.MAINTENANCE_ENGINEER);
    resolvesToStaff(engineer);

    // Without a mutation grant (even holding a usable read grant), the mutation is denied.
    usableDiagnosticsGrant(engineer.getId());
    assertThatThrownBy(() -> controller.recordMaintenance(tenantId, maintenanceRequest(), http))
        .isInstanceOf(SupportAccessDeniedException.class);
    verifyNoInteractions(maintenanceActionService);

    // Only an EXPLICIT, approved MAINTENANCE-scope grant permits the mutation, and then only that mutation.
    approvedMaintenanceGrant(engineer.getId());
    MaintenanceActionRecordResponse recorded = new MaintenanceActionRecordResponse(
        UUID.randomUUID(), tenantId, "RUNTIME_DIAGNOSTIC", "RECORDED", "runtime", T0);
    when(maintenanceActionService.record(tenantId, engineer.getId(), "RUNTIME_DIAGNOSTIC", "matrix-proof", "runtime"))
        .thenReturn(recorded);

    MaintenanceActionRecordResponse response =
        controller.recordMaintenance(tenantId, maintenanceRequest(), http);

    assertThat(response.status()).isEqualTo("RECORDED");
    verify(maintenanceActionService)
        .record(tenantId, engineer.getId(), "RUNTIME_DIAGNOSTIC", "matrix-proof", "runtime");
  }

  // --- 11. denied request does not invoke service / produce an allow side effect --------------------

  @Test
  void deniedDiagnosticsDoesNotInvokeDiagnosticsService() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    resolvesToStaff(staff);
    // A present-but-expired grant is still a denial: no read, no allow audit — only a denial audit.
    persistGrant(staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS, T0.minus(Duration.ofSeconds(1)));

    assertThatThrownBy(() -> controller.diagnostics(tenantId, http))
        .isInstanceOf(SupportAccessDeniedException.class);

    verifyNoInteractions(diagnosticsService);
    assertThat(countAudits("SUPPORT_ACCESS_GRANTED")).isZero();
    assertThat(countAudits("SUPPORT_ACCESS_DENIED")).isEqualTo(1);
  }

  // --- 12. allowed privileged action emits an audit -------------------------------------------------

  @Test
  void allowedSupportActionEmitsAuditIfCurrentAuditPathExists() {
    StaffUser engineer = activeStaff(StaffRole.MAINTENANCE_ENGINEER);
    resolvesToStaff(engineer);
    approvedMaintenanceGrant(engineer.getId());
    when(maintenanceActionService.record(any(), any(), any(), any(), any()))
        .thenReturn(new MaintenanceActionRecordResponse(
            UUID.randomUUID(), tenantId, "RUNTIME_DIAGNOSTIC", "RECORDED", "runtime", T0));

    controller.recordMaintenance(tenantId, maintenanceRequest(), http);

    // The support-access decision emits an allow audit for the privileged (mutation) action.
    assertThat(countAudits("SUPPORT_ACCESS_GRANTED")).isEqualTo(1);
  }
}
