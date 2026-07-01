package com.orderpilot.application.services.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.support.SupportAccessDeniedException;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.incident.AlertType;
import com.orderpilot.domain.incident.BreakGlassAccessRequest;
import com.orderpilot.domain.incident.BreakGlassAccessRequestRepository;
import com.orderpilot.domain.incident.BreakGlassScope;
import com.orderpilot.domain.incident.BreakGlassStatus;
import com.orderpilot.domain.incident.IncidentAlertRecordRepository;
import com.orderpilot.domain.incident.IncidentRecord;
import com.orderpilot.domain.incident.IncidentRecordRepository;
import com.orderpilot.domain.incident.IncidentStatus;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-53 — incident + break-glass lifecycle proof. An incident is audit-backed and a CRITICAL incident
 * cannot be silently closed; a break-glass request is unusable until a SEPARATE approver approves it, always
 * expires, and an expired/rejected/revoked request can never authorize. Every major transition is audited
 * and NO business row is mutated.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IncidentResponseServiceTest {
  private static final Instant T0 = Instant.parse("2026-06-26T12:00:00Z");

  @Autowired private IncidentRecordRepository incidentRepository;
  @Autowired private BreakGlassAccessRequestRepository breakGlassRepository;
  @Autowired private IncidentAlertRecordRepository alertRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ProcessingJobRepository processingJobRepository;

  private IncidentResponseService service;
  private UUID tenantId;

  private IncidentResponseService serviceAt(Instant instant) {
    AuditEventService audit = new AuditEventService(auditEventRepository, Clock.fixed(instant, ZoneOffset.UTC));
    return new IncidentResponseService(
        incidentRepository, breakGlassRepository, alertRepository, audit, new ObjectMapper(),
        Clock.fixed(instant, ZoneOffset.UTC));
  }

  @BeforeEach
  void setUp() {
    service = serviceAt(T0);
    tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private IncidentRecord openIncident(String severity) {
    return service.createIncident(
        tenantId, UUID.randomUUID(), "Stuck journey", "Investigate a stuck milestone", severity,
        "PRODUCTION_OUTAGE");
  }

  // --- incident lifecycle ---

  @Test
  void createIncidentPersistsOpenEmitsAuditAndMutatesNoBusinessRow() {
    IncidentRecord incident = openIncident("HIGH");

    assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
    assertThat(incidentRepository.count()).isEqualTo(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("INCIDENT_CREATED");
    assertThat(processingJobRepository.count()).isZero();
  }

  @Test
  void createCriticalIncidentRecordsRecordOnlyCriticalAlert() {
    IncidentRecord incident = openIncident("CRITICAL");

    assertThat(alertRepository.findByTenantIdAndIncidentIdOrderByCreatedAtDesc(
        tenantId, incident.getId()))
        .extracting("alertType")
        .contains(AlertType.CRITICAL_INCIDENT_CREATED);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("INCIDENT_ALERT_RECORDED");
  }

  @Test
  void missingTitleOrReasonIsRejected() {
    assertThatThrownBy(() -> service.createIncident(
        tenantId, UUID.randomUUID(), "  ", "reason", "HIGH", "PRODUCTION_OUTAGE"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.createIncident(
        tenantId, UUID.randomUUID(), "title", "  ", "HIGH", "PRODUCTION_OUTAGE"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(incidentRepository.count()).isZero();
  }

  @Test
  void unknownSeverityOrTypeIsRejected() {
    assertThatThrownBy(() -> service.createIncident(
        tenantId, UUID.randomUUID(), "t", "r", "CATASTROPHIC", "PRODUCTION_OUTAGE"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.createIncident(
        tenantId, UUID.randomUUID(), "t", "r", "HIGH", "ARBITRARY_TYPE"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(incidentRepository.count()).isZero();
  }

  @Test
  void closeCriticalIncidentWithoutClosureReasonIsDenied() {
    IncidentRecord incident = openIncident("CRITICAL");

    assertThatThrownBy(() -> service.closeIncident(tenantId, UUID.randomUUID(), incident.getId(), "  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(incidentRepository.findById(incident.getId()).orElseThrow().getStatus())
        .isEqualTo(IncidentStatus.OPEN);
  }

  @Test
  void closeIncidentWithReasonSucceedsAndEmitsAudit() {
    IncidentRecord incident = openIncident("CRITICAL");

    IncidentRecord closed = service.closeIncident(
        tenantId, UUID.randomUUID(), incident.getId(), "Milestone reconciled");

    assertThat(closed.getStatus()).isEqualTo(IncidentStatus.CLOSED);
    assertThat(closed.getClosedAt()).isNotNull();
    assertThat(closed.getClosureReason()).isEqualTo("Milestone reconciled");
    assertThat(auditEventRepository.findAll()).extracting("action").contains("INCIDENT_CLOSED");
  }

  @Test
  void closeAlreadyClosedIncidentIsConflict() {
    IncidentRecord incident = openIncident("LOW");
    service.closeIncident(tenantId, UUID.randomUUID(), incident.getId(), "done");

    assertThatThrownBy(() -> service.closeIncident(tenantId, UUID.randomUUID(), incident.getId(), "again"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void incidentIsTenantScoped() {
    IncidentRecord incident = openIncident("HIGH");
    assertThatThrownBy(() -> service.getIncident(UUID.randomUUID(), incident.getId()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void wrongTenantCannotCloseIncidentAndOriginalStateIsUnchanged() {
    IncidentRecord incident = openIncident("HIGH");

    assertThatThrownBy(() -> service.closeIncident(
        UUID.randomUUID(), UUID.randomUUID(), incident.getId(), "cross-tenant close"))
        .isInstanceOf(NotFoundException.class);

    assertThat(incidentRepository.findByIdAndTenantId(incident.getId(), tenantId).orElseThrow().getStatus())
        .isEqualTo(IncidentStatus.OPEN);
  }

  @Test
  void serviceRepositoryLookupsIncludeTenantBeforeReturningRecords() {
    IncidentRecordRepository incidents = mock(IncidentRecordRepository.class);
    BreakGlassAccessRequestRepository breakGlass = mock(BreakGlassAccessRequestRepository.class);
    IncidentAlertRecordRepository alerts = mock(IncidentAlertRecordRepository.class);
    AuditEventService audit = mock(AuditEventService.class);
    IncidentResponseService isolated = new IncidentResponseService(
        incidents, breakGlass, alerts, audit, new ObjectMapper(), Clock.fixed(T0, ZoneOffset.UTC));
    UUID scopedTenant = UUID.randomUUID();
    UUID incidentId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    when(incidents.findByIdAndTenantId(incidentId, scopedTenant)).thenReturn(Optional.empty());
    when(breakGlass.findByIdAndTenantId(requestId, scopedTenant)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> isolated.getIncident(scopedTenant, incidentId))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> isolated.approveBreakGlass(
        scopedTenant, UUID.randomUUID(), requestId, "decision"))
        .isInstanceOf(NotFoundException.class);

    verify(incidents).findByIdAndTenantId(incidentId, scopedTenant);
    verify(breakGlass).findByIdAndTenantId(requestId, scopedTenant);
    verify(incidents, never()).findById(incidentId);
    verify(breakGlass, never()).findById(requestId);
    verify(incidents, never()).save(any(IncidentRecord.class));
    verify(breakGlass, never()).save(any(BreakGlassAccessRequest.class));
    verifyNoInteractions(alerts, audit);
  }

  // --- break-glass lifecycle ---

  private BreakGlassAccessRequest request(IncidentRecord incident, UUID requester) {
    return service.requestBreakGlass(
        tenantId, requester, incident.getId(), "INCIDENT_DIAGNOSTICS", "Need emergency diagnostics",
        Duration.ofMinutes(30));
  }

  @Test
  void requestBreakGlassRequiresIncidentReasonScopeAndBoundedTtl() {
    IncidentRecord incident = openIncident("HIGH");
    UUID requester = UUID.randomUUID();
    BreakGlassAccessRequest req = request(incident, requester);

    assertThat(req.getStatus()).isEqualTo(BreakGlassStatus.REQUESTED);
    assertThat(req.getExpiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BREAK_GLASS_REQUESTED");
    assertThat(alertRepository.findByTenantIdAndIncidentIdOrderByCreatedAtDesc(
        tenantId, incident.getId()))
        .extracting("alertType").contains(AlertType.BREAK_GLASS_REQUESTED);
    assertThat(processingJobRepository.count()).isZero();
  }

  @Test
  void requestBreakGlassRejectsOverMaxOrNonPositiveTtlAndUnknownScope() {
    IncidentRecord incident = openIncident("HIGH");
    UUID requester = UUID.randomUUID();
    assertThatThrownBy(() -> service.requestBreakGlass(
        tenantId, requester, incident.getId(), "INCIDENT_DIAGNOSTICS", "r",
        IncidentResponseService.MAX_BREAK_GLASS_TTL.plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.requestBreakGlass(
        tenantId, requester, incident.getId(), "INCIDENT_DIAGNOSTICS", "r", Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.requestBreakGlass(
        tenantId, requester, incident.getId(), "ROOT_SHELL", "r", Duration.ofMinutes(10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(breakGlassRepository.count()).isZero();
  }

  @Test
  void pendingRequestIsNotUsable() {
    IncidentRecord incident = openIncident("HIGH");
    UUID requester = UUID.randomUUID();
    BreakGlassAccessRequest req = request(incident, requester);

    assertThatThrownBy(() -> service.authorize(
        tenantId, requester, BreakGlassScope.INCIDENT_DIAGNOSTICS, req.getId()))
        .isInstanceOf(SupportAccessDeniedException.class);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BREAK_GLASS_AUTHORIZATION_DENIED");
  }

  @Test
  void approvedRequestIsUsableUntilExpiry() {
    IncidentRecord incident = openIncident("HIGH");
    UUID requester = UUID.randomUUID();
    BreakGlassAccessRequest req = request(incident, requester);

    service.approveBreakGlass(tenantId, UUID.randomUUID(), req.getId(), "approved by IC");

    IncidentResponseService.BreakGlassSession session = service.authorize(
        tenantId, requester, BreakGlassScope.INCIDENT_DIAGNOSTICS, req.getId());
    assertThat(session.requestId()).isEqualTo(req.getId());
    assertThat(session.scope()).isEqualTo(BreakGlassScope.INCIDENT_DIAGNOSTICS);
    assertThat(auditEventRepository.findAll()).extracting("action")
        .contains("BREAK_GLASS_APPROVED", "BREAK_GLASS_AUTHORIZATION_GRANTED");
    assertThat(processingJobRepository.count()).isZero();
  }

  @Test
  void selfApprovalIsDenied() {
    IncidentRecord incident = openIncident("HIGH");
    UUID requester = UUID.randomUUID();
    BreakGlassAccessRequest req = request(incident, requester);

    assertThatThrownBy(() -> service.approveBreakGlass(tenantId, requester, req.getId(), "self"))
        .isInstanceOf(SupportAccessDeniedException.class);
    assertThat(breakGlassRepository.findById(req.getId()).orElseThrow().getStatus())
        .isEqualTo(BreakGlassStatus.REQUESTED);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BREAK_GLASS_APPROVAL_DENIED");
  }

  @Test
  void rejectedRequestIsUnusable() {
    IncidentRecord incident = openIncident("HIGH");
    UUID requester = UUID.randomUUID();
    BreakGlassAccessRequest req = request(incident, requester);

    service.rejectBreakGlass(tenantId, UUID.randomUUID(), req.getId(), "not justified");

    assertThat(breakGlassRepository.findById(req.getId()).orElseThrow().getStatus())
        .isEqualTo(BreakGlassStatus.REJECTED);
    assertThatThrownBy(() -> service.authorize(
        tenantId, requester, BreakGlassScope.INCIDENT_DIAGNOSTICS, req.getId()))
        .isInstanceOf(SupportAccessDeniedException.class);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BREAK_GLASS_REJECTED");
  }

  @Test
  void revokedRequestIsUnusable() {
    IncidentRecord incident = openIncident("HIGH");
    UUID requester = UUID.randomUUID();
    BreakGlassAccessRequest req = request(incident, requester);
    service.approveBreakGlass(tenantId, UUID.randomUUID(), req.getId(), "approved");

    service.revokeBreakGlass(tenantId, UUID.randomUUID(), req.getId(), "incident contained");

    assertThat(breakGlassRepository.findById(req.getId()).orElseThrow().getStatus())
        .isEqualTo(BreakGlassStatus.REVOKED);
    assertThatThrownBy(() -> service.authorize(
        tenantId, requester, BreakGlassScope.INCIDENT_DIAGNOSTICS, req.getId()))
        .isInstanceOf(SupportAccessDeniedException.class);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BREAK_GLASS_REVOKED");
  }

  @Test
  void expiredRequestIsUnusableAndRecordsExpiryAlert() {
    IncidentRecord incident = openIncident("HIGH");
    UUID requester = UUID.randomUUID();
    BreakGlassAccessRequest req = request(incident, requester);
    service.approveBreakGlass(tenantId, UUID.randomUUID(), req.getId(), "approved");

    IncidentResponseService later = serviceAt(T0.plus(Duration.ofHours(1)));
    assertThatThrownBy(() -> later.authorize(
        tenantId, requester, BreakGlassScope.INCIDENT_DIAGNOSTICS, req.getId()))
        .isInstanceOf(SupportAccessDeniedException.class);

    assertThat(breakGlassRepository.findById(req.getId()).orElseThrow().getStatus())
        .isEqualTo(BreakGlassStatus.EXPIRED);
    assertThat(alertRepository.findByTenantIdAndIncidentIdOrderByCreatedAtDesc(
        tenantId, incident.getId()))
        .extracting("alertType").contains(AlertType.BREAK_GLASS_EXPIRED);
  }

  @Test
  void closedIncidentCannotReceiveNewApprovedBreakGlass() {
    IncidentRecord incident = openIncident("HIGH");
    UUID requester = UUID.randomUUID();
    BreakGlassAccessRequest req = request(incident, requester);
    service.closeIncident(tenantId, UUID.randomUUID(), incident.getId(), "resolved");

    assertThatThrownBy(() -> service.approveBreakGlass(tenantId, UUID.randomUUID(), req.getId(), "late"))
        .isInstanceOf(ConflictException.class);
    assertThat(breakGlassRepository.findById(req.getId()).orElseThrow().getStatus())
        .isEqualTo(BreakGlassStatus.REQUESTED);
  }

  @Test
  void wrongTenantIsDenied() {
    IncidentRecord incident = openIncident("HIGH");
    UUID requester = UUID.randomUUID();
    BreakGlassAccessRequest req = request(incident, requester);
    service.approveBreakGlass(tenantId, UUID.randomUUID(), req.getId(), "approved");

    assertThatThrownBy(() -> service.authorize(
        UUID.randomUUID(), requester, BreakGlassScope.INCIDENT_DIAGNOSTICS, req.getId()))
        .isInstanceOf(SupportAccessDeniedException.class);
    assertThat(breakGlassRepository.findByIdAndTenantId(req.getId(), tenantId).orElseThrow().getStatus())
        .isEqualTo(BreakGlassStatus.APPROVED);
  }

  @Test
  void wrongTenantCannotApproveBreakGlassAndRequestRemainsPending() {
    IncidentRecord incident = openIncident("HIGH");
    BreakGlassAccessRequest req = request(incident, UUID.randomUUID());

    assertThatThrownBy(() -> service.approveBreakGlass(
        UUID.randomUUID(), UUID.randomUUID(), req.getId(), "cross-tenant approval"))
        .isInstanceOf(NotFoundException.class);

    assertThat(breakGlassRepository.findByIdAndTenantId(req.getId(), tenantId).orElseThrow().getStatus())
        .isEqualTo(BreakGlassStatus.REQUESTED);
  }

  @Test
  void wrongTenantCannotRequestBreakGlassAgainstForeignIncident() {
    IncidentRecord incident = openIncident("HIGH");

    assertThatThrownBy(() -> service.requestBreakGlass(
        UUID.randomUUID(),
        UUID.randomUUID(),
        incident.getId(),
        "INCIDENT_DIAGNOSTICS",
        "cross-tenant request",
        Duration.ofMinutes(10)))
        .isInstanceOf(NotFoundException.class);

    assertThat(breakGlassRepository.count()).isZero();
    assertThat(alertRepository.findByTenantIdAndIncidentIdOrderByCreatedAtDesc(
        tenantId, incident.getId())).isEmpty();
  }

  @Test
  void wrongTenantCannotRejectOrRevokeBreakGlassAndRequestsRemainPending() {
    IncidentRecord incident = openIncident("HIGH");
    BreakGlassAccessRequest rejectTarget = request(incident, UUID.randomUUID());
    BreakGlassAccessRequest revokeTarget = request(incident, UUID.randomUUID());
    UUID wrongTenant = UUID.randomUUID();

    assertThatThrownBy(() -> service.rejectBreakGlass(
        wrongTenant, UUID.randomUUID(), rejectTarget.getId(), "cross-tenant rejection"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.revokeBreakGlass(
        wrongTenant, UUID.randomUUID(), revokeTarget.getId(), "cross-tenant revocation"))
        .isInstanceOf(NotFoundException.class);

    assertThat(breakGlassRepository.findByIdAndTenantId(
        rejectTarget.getId(), tenantId).orElseThrow().getStatus())
        .isEqualTo(BreakGlassStatus.REQUESTED);
    assertThat(breakGlassRepository.findByIdAndTenantId(
        revokeTarget.getId(), tenantId).orElseThrow().getStatus())
        .isEqualTo(BreakGlassStatus.REQUESTED);
  }

  @Test
  void incidentAlertAndBreakGlassQueriesRequireTenantAndIncidentScope() {
    IncidentRecord incident = openIncident("CRITICAL");
    BreakGlassAccessRequest req = request(incident, UUID.randomUUID());
    UUID otherTenant = UUID.randomUUID();

    assertThat(alertRepository.findByTenantIdAndIncidentIdOrderByCreatedAtDesc(
        tenantId, incident.getId())).isNotEmpty();
    assertThat(breakGlassRepository.findByTenantIdAndIncidentIdOrderByRequestedAtDesc(
        tenantId, incident.getId())).extracting(BreakGlassAccessRequest::getId).containsExactly(req.getId());

    assertThat(alertRepository.findByTenantIdAndIncidentIdOrderByCreatedAtDesc(
        otherTenant, incident.getId())).isEmpty();
    assertThat(breakGlassRepository.findByTenantIdAndIncidentIdOrderByRequestedAtDesc(
        otherTenant, incident.getId())).isEmpty();
    assertThat(breakGlassRepository.findByTenantIdAndIncidentIdOrderByRequestedAtDesc(
        tenantId, UUID.randomUUID())).isEmpty();
  }

  @Test
  void wrongScopeIsDenied() {
    IncidentRecord incident = openIncident("HIGH");
    UUID requester = UUID.randomUUID();
    BreakGlassAccessRequest req = request(incident, requester);
    service.approveBreakGlass(tenantId, UUID.randomUUID(), req.getId(), "approved");

    assertThatThrownBy(() -> service.authorize(
        tenantId, requester, BreakGlassScope.CONNECTOR_FREEZE, req.getId()))
        .isInstanceOf(SupportAccessDeniedException.class);
  }

  @Test
  void wrongActorIsDenied() {
    IncidentRecord incident = openIncident("HIGH");
    UUID requester = UUID.randomUUID();
    BreakGlassAccessRequest req = request(incident, requester);
    service.approveBreakGlass(tenantId, UUID.randomUUID(), req.getId(), "approved");

    // The grant is for the requester only — a different staff actor cannot use it.
    assertThatThrownBy(() -> service.authorize(
        tenantId, UUID.randomUUID(), BreakGlassScope.INCIDENT_DIAGNOSTICS, req.getId()))
        .isInstanceOf(SupportAccessDeniedException.class);
  }
}
