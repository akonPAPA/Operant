package com.orderpilot.application.services.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportOperationsDtos.DataRepairOperationsViewResponse;
import com.orderpilot.api.dto.SupportOperationsDtos.SupportOperationsSummaryResponse;
import com.orderpilot.api.dto.SupportOperationsDtos.SupportOperationsTimelineEntry;
import com.orderpilot.api.dto.SupportOperationsDtos.SupportOperationsTimelineResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.incident.BreakGlassAccessRequest;
import com.orderpilot.domain.incident.BreakGlassAccessRequestRepository;
import com.orderpilot.domain.incident.BreakGlassStatus;
import com.orderpilot.domain.incident.IncidentRecord;
import com.orderpilot.domain.incident.IncidentRecordRepository;
import com.orderpilot.domain.incident.IncidentSeverity;
import com.orderpilot.domain.incident.IncidentStatus;
import com.orderpilot.domain.support.DataRepairRequest;
import com.orderpilot.domain.support.DataRepairRequestRepository;
import com.orderpilot.domain.support.DataRepairTargetType;
import com.orderpilot.domain.support.SupportAccessGrant;
import com.orderpilot.domain.support.SupportAccessGrantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-55 — the single authority for the READ-ONLY internal support operations visibility surface. It
 * aggregates safe, backend-owned counts and lifecycle markers from the existing support, incident,
 * break-glass, and data-repair records so Operant-owner support staff can inspect operations state.
 *
 * <p>This stage adds NO new mutation and NO new executor. The service NEVER mutates any business row, runs
 * SQL/script, calls a connector/ERP, or returns a secret/credential/raw payload. Every query is tenant-scoped
 * and bounded — the timeline never streams an unbounded full table (capped page size and a capped scan
 * window), and the summary uses tenant-scoped count queries.
 *
 * <p>Route-edge authorization ({@code STAFF_SUPPORT_READ}) and the second-layer scoped support grant
 * ({@code DIAGNOSTICS}) are enforced by the controller before any method here runs; each read additionally
 * emits a dedicated "viewed" audit event (no secret/payload).
 */
@Service
public class SupportOperationsService {
  /** Default timeline page size when the caller does not specify one. */
  public static final int DEFAULT_PAGE_SIZE = 20;
  /** Hard ceiling on the timeline page size — a caller can never request an unbounded page. */
  public static final int MAX_PAGE_SIZE = 50;
  /** Hard ceiling on how far the timeline may be scanned, bounding every per-source fetch. */
  public static final int MAX_TIMELINE_WINDOW = 500;

  private static final String EXTERNAL_EXECUTION_DISABLED = "DISABLED";
  private static final String ENTITY_OPERATIONS = "SUPPORT_OPERATIONS";

  private final IncidentRecordRepository incidentRepository;
  private final BreakGlassAccessRequestRepository breakGlassRepository;
  private final SupportAccessGrantRepository grantRepository;
  private final DataRepairRequestRepository dataRepairRepository;
  private final AuditEventService auditEventService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public SupportOperationsService(
      IncidentRecordRepository incidentRepository,
      BreakGlassAccessRequestRepository breakGlassRepository,
      SupportAccessGrantRepository grantRepository,
      DataRepairRequestRepository dataRepairRepository,
      AuditEventService auditEventService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.incidentRepository = incidentRepository;
    this.breakGlassRepository = breakGlassRepository;
    this.grantRepository = grantRepository;
    this.dataRepairRepository = dataRepairRepository;
    this.auditEventService = auditEventService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  // --- 1) operations summary ---

  /**
   * Build a bounded, safe operations summary (counts + latest safe activity timestamp) for one tenant. All
   * counts are computed with tenant-scoped count queries; no raw record content is exposed.
   */
  @Transactional(readOnly = true)
  public SupportOperationsSummaryResponse summary(UUID tenantId, UUID staffActor) {
    Instant now = clock.instant();
    long openIncidents = incidentRepository.countByTenantIdAndStatus(tenantId, IncidentStatus.OPEN);
    long criticalOpenIncidents = incidentRepository.countByTenantIdAndStatusAndSeverity(
        tenantId, IncidentStatus.OPEN, IncidentSeverity.CRITICAL);
    long pendingBreakGlass = breakGlassRepository.countByTenantIdAndStatus(tenantId, BreakGlassStatus.REQUESTED);
    long approvedBreakGlass =
        breakGlassRepository.countByTenantIdAndStatusAndExpiresAtAfter(tenantId, BreakGlassStatus.APPROVED, now);
    long pendingGrants = grantRepository.countByTenantIdAndApprovalStatus(
        tenantId, SupportAccessGrant.ApprovalStatus.PENDING_APPROVAL);
    long activeGrants = grantRepository.countByTenantIdAndStatusAndApprovalStatusInAndExpiresAtAfter(
        tenantId,
        SupportAccessGrant.Status.ACTIVE,
        List.of(SupportAccessGrant.ApprovalStatus.AUTO_APPROVED, SupportAccessGrant.ApprovalStatus.APPROVED),
        now);
    long pendingDataRepairApprovals = dataRepairRepository.countByTenantIdAndApprovalStatus(
        tenantId, DataRepairRequest.ApprovalStatus.PENDING_APPROVAL);
    long approvedDataRepair = dataRepairRepository.countByTenantIdAndApprovalStatus(
        tenantId, DataRepairRequest.ApprovalStatus.APPROVED);
    long rejectedDataRepair = dataRepairRepository.countByTenantIdAndApprovalStatus(
        tenantId, DataRepairRequest.ApprovalStatus.REJECTED);
    long executedRepairs = dataRepairRepository.countByTenantIdAndExecutionStatus(
        tenantId, DataRepairRequest.ExecutionStatus.EXECUTED);

    Instant latestActivity = latestActivity(tenantId);

    audit("SUPPORT_OPERATIONS_SUMMARY_VIEWED", tenantId.toString(), staffActor, map -> {
      map.put("tenantId", tenantId.toString());
      map.put("openIncidents", openIncidents);
      map.put("executedProcessingJobRepairs", executedRepairs);
    });

    return new SupportOperationsSummaryResponse(
        tenantId,
        openIncidents,
        criticalOpenIncidents,
        pendingBreakGlass,
        approvedBreakGlass,
        pendingGrants,
        activeGrants,
        pendingDataRepairApprovals,
        approvedDataRepair,
        executedRepairs,
        rejectedDataRepair,
        latestActivity,
        now,
        EXTERNAL_EXECUTION_DISABLED);
  }

  private Instant latestActivity(UUID tenantId) {
    Instant latest = null;
    latest = max(latest, incidentRepository.findFirstByTenantIdOrderByUpdatedAtDesc(tenantId)
        .map(IncidentRecord::getUpdatedAt).orElse(null));
    latest = max(latest, breakGlassRepository.findFirstByTenantIdOrderByRequestedAtDesc(tenantId)
        .map(BreakGlassAccessRequest::getRequestedAt).orElse(null));
    latest = max(latest, grantRepository.findFirstByTenantIdOrderByCreatedAtDesc(tenantId)
        .map(SupportAccessGrant::getCreatedAt).orElse(null));
    DataRepairRequest latestRepair = dataRepairRepository.findFirstByTenantIdOrderByCreatedAtDesc(tenantId)
        .orElse(null);
    if (latestRepair != null) {
      latest = max(latest, latestRepair.getCreatedAt());
      latest = max(latest, latestRepair.getExecutedAt());
    }
    return latest;
  }

  // --- 2) operations timeline ---

  /**
   * Build a bounded, deterministically time-ordered (desc) page of safe operations lifecycle markers across
   * incidents, break-glass requests, support grants, data-repair requests, and processing-job repair
   * executions. Page size is clamped to {@link #MAX_PAGE_SIZE}; each per-source fetch is bounded by the
   * requested window (capped at {@link #MAX_TIMELINE_WINDOW}), so this never performs an unbounded scan.
   */
  @Transactional(readOnly = true)
  public SupportOperationsTimelineResponse timeline(UUID tenantId, UUID staffActor, int requestedPage, int requestedSize) {
    int page = Math.max(0, requestedPage);
    int size = requestedSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(requestedSize, MAX_PAGE_SIZE);
    int offset = page * size;
    // Bound how much we scan: only enough to serve this page within the capped window.
    int fetchLimit = Math.min(offset + size, MAX_TIMELINE_WINDOW);
    PageRequest window = PageRequest.of(0, Math.max(fetchLimit, 1));

    List<SupportOperationsTimelineEntry> all = new ArrayList<>();
    for (IncidentRecord incident : incidentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, window)) {
      addIncidentEvents(all, incident);
    }
    for (BreakGlassAccessRequest request : breakGlassRepository.findByTenantIdOrderByRequestedAtDesc(tenantId, window)) {
      addBreakGlassEvents(all, request);
    }
    for (SupportAccessGrant grant : grantRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, window)) {
      addGrantEvents(all, grant);
    }
    for (DataRepairRequest repair : dataRepairRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, window)) {
      addDataRepairEvents(all, repair);
    }

    all.sort(TIMELINE_ORDER);

    List<SupportOperationsTimelineEntry> pageEntries = offset >= all.size()
        ? List.of()
        : List.copyOf(all.subList(offset, Math.min(offset + size, all.size())));
    boolean hasMore = all.size() > offset + size;

    audit("SUPPORT_OPERATIONS_TIMELINE_VIEWED", tenantId.toString(), staffActor, map -> {
      map.put("tenantId", tenantId.toString());
      map.put("page", page);
      map.put("pageSize", size);
      map.put("returnedCount", pageEntries.size());
    });

    return new SupportOperationsTimelineResponse(
        tenantId, page, size, pageEntries.size(), hasMore, pageEntries, clock.instant());
  }

  // --- 3) data-repair operations detail view ---

  /**
   * Build the operations detail view of one tenant-scoped data-repair request, including its bounded
   * processing-job repair execution result (if any) and a derived safe per-request lifecycle timeline. A
   * request from another tenant is never found (404). Exposes no actor id, raw reason, SQL, or secret.
   */
  @Transactional(readOnly = true)
  public DataRepairOperationsViewResponse dataRepairOperationsView(UUID tenantId, UUID staffActor, UUID requestId) {
    DataRepairRequest request = dataRepairRepository.findByIdAndTenantId(requestId, tenantId)
        .orElseThrow(() -> new NotFoundException("Data-repair request not found"));

    List<SupportOperationsTimelineEntry> timeline = new ArrayList<>();
    addDataRepairEvents(timeline, request);
    timeline.sort(TIMELINE_ORDER);

    boolean processingJobTarget = request.getTargetType() == DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR;

    audit("SUPPORT_OPERATIONS_DATA_REPAIR_VIEWED", request.getId().toString(), staffActor, map -> {
      map.put("tenantId", tenantId.toString());
      map.put("requestId", request.getId().toString());
      map.put("executionStatus", request.getExecutionStatus().name());
    });

    return new DataRepairOperationsViewResponse(
        request.getId(),
        request.getTenantId(),
        request.getTargetType().name(),
        request.getApprovalStatus().name(),
        request.getExecutionStatus().name(),
        request.getStatus().name(),
        request.getAffectedTargetSummary(),
        processingJobTarget ? request.getTargetProcessingJobId() : null,
        request.getPreviousStatus(),
        request.getNewStatus(),
        request.getExecutedAt(),
        request.isExecuted(),
        List.copyOf(timeline),
        clock.instant(),
        EXTERNAL_EXECUTION_DISABLED);
  }

  // --- lifecycle marker builders (derived from backend-owned record timestamps/status, not audit rows) ---

  private static void addIncidentEvents(List<SupportOperationsTimelineEntry> sink, IncidentRecord incident) {
    sink.add(entry("INCIDENT", "INCIDENT_CREATED", incident.getId(), incident.getStatus().name(),
        incident.getCreatedAt()));
    if (incident.getClosedAt() != null) {
      sink.add(entry("INCIDENT", "INCIDENT_CLOSED", incident.getId(), incident.getStatus().name(),
          incident.getClosedAt()));
    }
  }

  private static void addBreakGlassEvents(List<SupportOperationsTimelineEntry> sink, BreakGlassAccessRequest request) {
    String status = request.getStatus().name();
    sink.add(entry("BREAK_GLASS", "BREAK_GLASS_REQUESTED", request.getId(), status, request.getRequestedAt()));
    if (request.getDecidedAt() != null) {
      if (request.getStatus() == BreakGlassStatus.APPROVED) {
        sink.add(entry("BREAK_GLASS", "BREAK_GLASS_APPROVED", request.getId(), status, request.getDecidedAt()));
      } else if (request.getStatus() == BreakGlassStatus.REJECTED) {
        sink.add(entry("BREAK_GLASS", "BREAK_GLASS_REJECTED", request.getId(), status, request.getDecidedAt()));
      }
    }
    if (request.getRevokedAt() != null) {
      sink.add(entry("BREAK_GLASS", "BREAK_GLASS_REVOKED", request.getId(), status, request.getRevokedAt()));
    }
    if (request.getStatus() == BreakGlassStatus.EXPIRED) {
      sink.add(entry("BREAK_GLASS", "BREAK_GLASS_EXPIRED", request.getId(), status, request.getExpiresAt()));
    }
  }

  private static void addGrantEvents(List<SupportOperationsTimelineEntry> sink, SupportAccessGrant grant) {
    String approval = grant.getApprovalStatus().name();
    sink.add(entry("SUPPORT_GRANT", "SUPPORT_GRANT_CREATED", grant.getId(), approval, grant.getCreatedAt()));
    if (grant.getApprovalDecidedAt() != null) {
      if (grant.getApprovalStatus() == SupportAccessGrant.ApprovalStatus.APPROVED) {
        sink.add(entry("SUPPORT_GRANT", "SUPPORT_GRANT_APPROVED", grant.getId(), approval,
            grant.getApprovalDecidedAt()));
      } else if (grant.getApprovalStatus() == SupportAccessGrant.ApprovalStatus.REJECTED) {
        sink.add(entry("SUPPORT_GRANT", "SUPPORT_GRANT_REJECTED", grant.getId(), approval,
            grant.getApprovalDecidedAt()));
      }
    }
    if (grant.getRevokedAt() != null) {
      sink.add(entry("SUPPORT_GRANT", "SUPPORT_GRANT_REVOKED", grant.getId(), grant.getStatus().name(),
          grant.getRevokedAt()));
    }
  }

  private static void addDataRepairEvents(List<SupportOperationsTimelineEntry> sink, DataRepairRequest repair) {
    String approval = repair.getApprovalStatus().name();
    boolean processingJob = repair.getTargetType() == DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR;
    sink.add(entry("DATA_REPAIR", "DATA_REPAIR_DRY_RUN", repair.getId(), approval, repair.getCreatedAt()));
    if (repair.getApprovalRequestedAt() != null) {
      sink.add(entry("DATA_REPAIR", "DATA_REPAIR_APPROVAL_REQUESTED", repair.getId(), approval,
          repair.getApprovalRequestedAt()));
    }
    if (repair.getApprovalDecidedAt() != null) {
      if (repair.getApprovalStatus() == DataRepairRequest.ApprovalStatus.APPROVED) {
        sink.add(entry("DATA_REPAIR", "DATA_REPAIR_APPROVED", repair.getId(), approval,
            repair.getApprovalDecidedAt()));
      } else if (repair.getApprovalStatus() == DataRepairRequest.ApprovalStatus.REJECTED) {
        sink.add(entry("DATA_REPAIR", "DATA_REPAIR_REJECTED", repair.getId(), approval,
            repair.getApprovalDecidedAt()));
      }
    }
    if (repair.getExecutedAt() != null) {
      String category = processingJob ? "PROCESSING_JOB_REPAIR" : "DATA_REPAIR";
      String eventType = processingJob ? "PROCESSING_JOB_REPAIR_EXECUTED" : "DATA_REPAIR_EXECUTED";
      sink.add(entry(category, eventType, repair.getId(), repair.getExecutionStatus().name(),
          repair.getExecutedAt()));
    }
  }

  private static SupportOperationsTimelineEntry entry(
      String category, String eventType, UUID referenceId, String status, Instant occurredAt) {
    return new SupportOperationsTimelineEntry(category, eventType, referenceId, status, occurredAt);
  }

  // Deterministic ordering: newest first, tie-broken by event type then reference id so a page is stable.
  private static final Comparator<SupportOperationsTimelineEntry> TIMELINE_ORDER =
      Comparator.comparing(SupportOperationsTimelineEntry::occurredAt, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(SupportOperationsTimelineEntry::eventType)
          .thenComparing(e -> e.referenceId() == null ? "" : e.referenceId().toString());

  private static Instant max(Instant current, Instant candidate) {
    if (candidate == null) {
      return current;
    }
    if (current == null || candidate.isAfter(current)) {
      return candidate;
    }
    return current;
  }

  private void audit(String action, String entityId, UUID actorId,
      java.util.function.Consumer<Map<String, Object>> builder) {
    Map<String, Object> map = new LinkedHashMap<>();
    builder.accept(map);
    String metadata;
    try {
      metadata = objectMapper.writeValueAsString(map);
    } catch (Exception ex) {
      metadata = "{}";
    }
    auditEventService.record(action, ENTITY_OPERATIONS, entityId, actorId, metadata);
  }
}
