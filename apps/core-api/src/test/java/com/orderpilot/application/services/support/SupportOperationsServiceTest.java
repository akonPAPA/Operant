package com.orderpilot.application.services.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportOperationsDtos.DataRepairOperationsViewResponse;
import com.orderpilot.api.dto.SupportOperationsDtos.SupportOperationsSummaryResponse;
import com.orderpilot.api.dto.SupportOperationsDtos.SupportOperationsTimelineEntry;
import com.orderpilot.api.dto.SupportOperationsDtos.SupportOperationsTimelineResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.incident.BreakGlassAccessRequest;
import com.orderpilot.domain.incident.BreakGlassAccessRequestRepository;
import com.orderpilot.domain.incident.BreakGlassScope;
import com.orderpilot.domain.incident.IncidentRecord;
import com.orderpilot.domain.incident.IncidentRecordRepository;
import com.orderpilot.domain.incident.IncidentSeverity;
import com.orderpilot.domain.incident.IncidentType;
import com.orderpilot.domain.support.DataRepairRequest;
import com.orderpilot.domain.support.DataRepairRequestRepository;
import com.orderpilot.domain.support.DataRepairTargetType;
import com.orderpilot.domain.support.StaffSupportScope;
import com.orderpilot.domain.support.SupportAccessGrant;
import com.orderpilot.domain.support.SupportAccessGrantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-55 — JPA-backed proof for the internal support operations visibility read model. Verifies
 * tenant scoping, active-count semantics, bounded timeline paging, safe processing-job repair detail, and
 * read audit emission without adding any new executor or business-table mutation.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SupportOperationsServiceTest {
  private static final Instant T0 = Instant.parse("2026-06-26T12:00:00Z");

  @Autowired private IncidentRecordRepository incidentRepository;
  @Autowired private BreakGlassAccessRequestRepository breakGlassRepository;
  @Autowired private SupportAccessGrantRepository grantRepository;
  @Autowired private DataRepairRequestRepository dataRepairRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  private UUID tenantId;
  private UUID staffActor;

  @BeforeEach
  void setUp() {
    tenantId = UUID.randomUUID();
    staffActor = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private SupportOperationsService serviceAt(Instant now) {
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    return new SupportOperationsService(
        incidentRepository,
        breakGlassRepository,
        grantRepository,
        dataRepairRepository,
        new AuditEventService(auditEventRepository, clock),
        new ObjectMapper(),
        clock);
  }

  private DataRepairRequest processingRepair(UUID tenant, Instant now) {
    DataRepairRequest request = new DataRepairRequest(
        tenant, DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR, staffActor, "stuck job", now);
    request.requestApproval("1 stuck processing job", now.plus(Duration.ofHours(1)), now);
    request.approve(UUID.randomUUID(), "ok", now.plusSeconds(1));
    request.recordProcessingJobRepairExecution(UUID.randomUUID(), "PROCESSING", "FAILED", staffActor, now.plusSeconds(2));
    return dataRepairRepository.save(request);
  }

  @Test
  void summaryCountsOnlyTenantScopedActiveApprovedRecordsAndAuditsRead() {
    incidentRepository.save(new IncidentRecord(
        tenantId, "critical", "reason", IncidentSeverity.CRITICAL, IncidentType.PRODUCTION_OUTAGE, staffActor, T0));
    incidentRepository.save(new IncidentRecord(
        UUID.randomUUID(), "other", "reason", IncidentSeverity.CRITICAL, IncidentType.PRODUCTION_OUTAGE,
        staffActor, T0));

    BreakGlassAccessRequest activeBreakGlass = new BreakGlassAccessRequest(
        tenantId, UUID.randomUUID(), staffActor, BreakGlassScope.INCIDENT_DIAGNOSTICS, "reason", T0, T0.plusSeconds(60));
    activeBreakGlass.approve(UUID.randomUUID(), T0.plusSeconds(1));
    breakGlassRepository.save(activeBreakGlass);
    BreakGlassAccessRequest expiredBreakGlass = new BreakGlassAccessRequest(
        tenantId, UUID.randomUUID(), staffActor, BreakGlassScope.INCIDENT_DIAGNOSTICS, "reason", T0, T0.minusSeconds(1));
    expiredBreakGlass.approve(UUID.randomUUID(), T0.plusSeconds(1));
    breakGlassRepository.save(expiredBreakGlass);

    grantRepository.save(new SupportAccessGrant(
        staffActor, tenantId, StaffSupportScope.DIAGNOSTICS, "case", T0.plusSeconds(60), staffActor, T0));
    grantRepository.save(new SupportAccessGrant(
        UUID.randomUUID(), tenantId, StaffSupportScope.DIAGNOSTICS, "expired", T0.minusSeconds(1), staffActor, T0));
    grantRepository.save(new SupportAccessGrant(
        UUID.randomUUID(), tenantId, StaffSupportScope.DATA_REPAIR, "pending", T0.plusSeconds(60), staffActor, T0));
    processingRepair(tenantId, T0);

    SupportOperationsSummaryResponse response = serviceAt(T0).summary(tenantId, staffActor);

    assertThat(response.openIncidents()).isEqualTo(1);
    assertThat(response.criticalOpenIncidents()).isEqualTo(1);
    assertThat(response.approvedActiveBreakGlassRequests()).isEqualTo(1);
    assertThat(response.activeSupportGrants()).isEqualTo(1);
    assertThat(response.pendingSupportGrants()).isEqualTo(1);
    assertThat(response.executedProcessingJobRepairs()).isEqualTo(1);
    assertThat(response.externalExecution()).isEqualTo("DISABLED");
    assertThat(auditEventRepository.findAll()).anyMatch(e -> "SUPPORT_OPERATIONS_SUMMARY_VIEWED".equals(e.getAction()));
  }

  @Test
  void timelineIsBoundedPagedAndSortedDescendingWithoutCrossTenantEvents() {
    IncidentRecord oldest = incidentRepository.save(new IncidentRecord(
        tenantId, "old", "reason", IncidentSeverity.MEDIUM, IncidentType.SUPPORT_ESCALATION, staffActor, T0));
    oldest.close("done", T0.plusSeconds(10));
    incidentRepository.save(oldest);
    processingRepair(tenantId, T0.plusSeconds(20));
    incidentRepository.save(new IncidentRecord(
        UUID.randomUUID(), "other", "reason", IncidentSeverity.CRITICAL, IncidentType.PRODUCTION_OUTAGE,
        staffActor, T0.plusSeconds(100)));

    SupportOperationsTimelineResponse response = serviceAt(T0.plusSeconds(30)).timeline(tenantId, staffActor, 0, 999);

    assertThat(response.pageSize()).isEqualTo(SupportOperationsService.MAX_PAGE_SIZE);
    assertThat(response.entries()).isNotEmpty();
    assertThat(response.entries()).extracting(SupportOperationsTimelineEntry::occurredAt)
        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    assertThat(response.entries()).extracting(SupportOperationsTimelineEntry::eventType)
        .contains("INCIDENT_CREATED", "INCIDENT_CLOSED", "PROCESSING_JOB_REPAIR_EXECUTED")
        .doesNotContain("OTHER_TENANT_EVENT");
    assertThat(auditEventRepository.findAll()).anyMatch(e -> "SUPPORT_OPERATIONS_TIMELINE_VIEWED".equals(e.getAction()));
  }

  @Test
  void dataRepairOperationsViewReturnsSafeProcessingJobResultAndHidesOtherTenants() {
    DataRepairRequest request = processingRepair(tenantId, T0);

    DataRepairOperationsViewResponse response =
        serviceAt(T0.plusSeconds(5)).dataRepairOperationsView(tenantId, staffActor, request.getId());

    assertThat(response.requestId()).isEqualTo(request.getId());
    assertThat(response.tenantId()).isEqualTo(tenantId);
    assertThat(response.targetType()).isEqualTo("PROCESSING_JOB_STATUS_REPAIR");
    assertThat(response.executionStatus()).isEqualTo("EXECUTED");
    assertThat(response.processingJobId()).isEqualTo(request.getTargetProcessingJobId());
    assertThat(response.previousStatus()).isEqualTo("PROCESSING");
    assertThat(response.newStatus()).isEqualTo("FAILED");
    assertThat(response.executed()).isTrue();
    assertThat(response.externalExecution()).isEqualTo("DISABLED");
    assertThat(response.timeline()).extracting(SupportOperationsTimelineEntry::eventType)
        .contains("DATA_REPAIR_DRY_RUN", "DATA_REPAIR_APPROVED", "PROCESSING_JOB_REPAIR_EXECUTED");
    assertThat(auditEventRepository.findAll())
        .anyMatch(e -> "SUPPORT_OPERATIONS_DATA_REPAIR_VIEWED".equals(e.getAction()));

    assertThatThrownBy(() -> serviceAt(T0).dataRepairOperationsView(UUID.randomUUID(), staffActor, request.getId()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void emptyTimelineReturnsSafeEmptyPage() {
    SupportOperationsTimelineResponse response = serviceAt(T0).timeline(tenantId, staffActor, 0, 20);

    assertThat(response.entries()).isEmpty();
    assertThat(response.returnedCount()).isZero();
    assertThat(response.hasMore()).isFalse();
  }
}
