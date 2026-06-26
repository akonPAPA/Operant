package com.orderpilot.application.services.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.ConflictException;
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
 * OP-CAP-52 — support grant approval workflow. Proves a sensitive grant is born PENDING_APPROVAL and cannot
 * authorize access until approved; a rejected/expired grant denies; the approval state is backend-owned;
 * an approver cannot approve their own grant request (separation of duties); and every decision is audited.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SupportGrantApprovalServiceTest {
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

  private long countAudits(String action) {
    return auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction())).count();
  }

  @Test
  void lowRiskDiagnosticsGrantIsAutoApprovedAndImmediatelyUsable() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);

    SupportAccessGrant grant = service.createGrant(
        staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS, "CASE-1", Duration.ofHours(1), UUID.randomUUID());

    assertThat(grant.getApprovalStatus()).isEqualTo(SupportAccessGrant.ApprovalStatus.AUTO_APPROVED);
    assertThat(grant.isUsable(T0)).isTrue();
    // No approval request emitted for an auto-approved low-risk grant.
    assertThat(countAudits("SUPPORT_GRANT_APPROVAL_REQUESTED")).isZero();
  }

  @Test
  void sensitiveGrantIsPendingAndNotUsableUntilApproved() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);

    SupportAccessGrant grant = service.createGrant(
        staff.getId(), tenantId, StaffSupportScope.DATA_REPAIR, "CASE-2", Duration.ofHours(1), UUID.randomUUID());

    assertThat(grant.getApprovalStatus()).isEqualTo(SupportAccessGrant.ApprovalStatus.PENDING_APPROVAL);
    assertThat(grant.isUsable(T0)).isFalse();
    // A pending grant cannot authorize access.
    assertThatThrownBy(() -> service.authorize(staff.getId(), tenantId, StaffSupportScope.DATA_REPAIR))
        .isInstanceOf(SupportAccessDeniedException.class);
    assertThat(countAudits("SUPPORT_GRANT_APPROVAL_REQUESTED")).isEqualTo(1);
  }

  @Test
  void approvedSensitiveGrantAuthorizesAndIsBackendOwnedAndAudited() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    UUID creator = UUID.randomUUID();
    UUID approver = UUID.randomUUID();
    SupportAccessGrant grant = service.createGrant(
        staff.getId(), tenantId, StaffSupportScope.MAINTENANCE, "CASE-3", Duration.ofHours(1), creator);

    SupportAccessGrant approved = service.approveGrant(grant.getId(), tenantId, approver, "looks fine");

    assertThat(approved.getApprovalStatus()).isEqualTo(SupportAccessGrant.ApprovalStatus.APPROVED);
    assertThat(approved.getApprovedBy()).isEqualTo(approver);
    assertThat(approved.getApprovalDecidedAt()).isEqualTo(T0);
    assertThat(countAudits("SUPPORT_GRANT_APPROVED")).isEqualTo(1);
    // Now authorization succeeds.
    SupportAccessService.SupportSession session =
        service.authorize(staff.getId(), tenantId, StaffSupportScope.MAINTENANCE);
    assertThat(session.grantId()).isEqualTo(grant.getId());
  }

  @Test
  void rejectedGrantCannotAuthorize() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    SupportAccessGrant grant = service.createGrant(
        staff.getId(), tenantId, StaffSupportScope.DATA_REPAIR, "CASE-4", Duration.ofHours(1), UUID.randomUUID());

    SupportAccessGrant rejected = service.rejectGrant(grant.getId(), tenantId, UUID.randomUUID(), "denied");

    assertThat(rejected.getApprovalStatus()).isEqualTo(SupportAccessGrant.ApprovalStatus.REJECTED);
    assertThat(rejected.isUsable(T0)).isFalse();
    assertThatThrownBy(() -> service.authorize(staff.getId(), tenantId, StaffSupportScope.DATA_REPAIR))
        .isInstanceOf(SupportAccessDeniedException.class);
    assertThat(countAudits("SUPPORT_GRANT_REJECTED")).isEqualTo(1);
  }

  @Test
  void expiredApprovedGrantIsDenied() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    // An already-expired grant that was approved must still be denied (expiry wins over approval).
    SupportAccessGrant grant = new SupportAccessGrant(
        staff.getId(), tenantId, StaffSupportScope.DATA_REPAIR, "CASE-5", T0.minus(Duration.ofMinutes(1)),
        UUID.randomUUID(), T0.minus(Duration.ofHours(2)));
    grant.approve(UUID.randomUUID(), "ok", T0.minus(Duration.ofHours(1)));
    grantRepository.save(grant);

    assertThat(grant.isUsable(T0)).isFalse();
    assertThatThrownBy(() -> service.authorize(staff.getId(), tenantId, StaffSupportScope.DATA_REPAIR))
        .isInstanceOf(SupportAccessDeniedException.class);
  }

  @Test
  void approverCannotApproveTheirOwnGrantRequest() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    UUID creator = UUID.randomUUID();
    SupportAccessGrant grant = service.createGrant(
        staff.getId(), tenantId, StaffSupportScope.DATA_REPAIR, "CASE-6", Duration.ofHours(1), creator);

    // The same actor who created the grant request cannot approve it.
    assertThatThrownBy(() -> service.approveGrant(grant.getId(), tenantId, creator, "self"))
        .isInstanceOf(SupportAccessDeniedException.class);
    assertThat(grantRepository.findById(grant.getId()).orElseThrow().getApprovalStatus())
        .isEqualTo(SupportAccessGrant.ApprovalStatus.PENDING_APPROVAL);
    assertThat(countAudits("SUPPORT_GRANT_APPROVAL_DENIED")).isEqualTo(1);
  }

  @Test
  void approvingANonPendingGrantIsAConflict() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    // Auto-approved low-risk grant is not pending — approving it is a conflict.
    SupportAccessGrant grant = service.createGrant(
        staff.getId(), tenantId, StaffSupportScope.DIAGNOSTICS, "CASE-7", Duration.ofHours(1), UUID.randomUUID());

    assertThatThrownBy(() -> service.approveGrant(grant.getId(), tenantId, UUID.randomUUID(), null))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void approvingGrantFromAnotherTenantIsNotFound() {
    StaffUser staff = activeStaff(StaffRole.SUPPORT_ENGINEER);
    SupportAccessGrant grant = service.createGrant(
        staff.getId(), tenantId, StaffSupportScope.DATA_REPAIR, "CASE-8", Duration.ofHours(1), UUID.randomUUID());

    assertThatThrownBy(() -> service.approveGrant(grant.getId(), UUID.randomUUID(), UUID.randomUUID(), null))
        .isInstanceOf(com.orderpilot.common.errors.NotFoundException.class);
  }
}
