package com.orderpilot.application.services.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.support.SupportAccessDeniedException;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.incident.AlertType;
import com.orderpilot.domain.incident.BreakGlassAccessRequest;
import com.orderpilot.domain.incident.BreakGlassAccessRequestRepository;
import com.orderpilot.domain.incident.BreakGlassScope;
import com.orderpilot.domain.incident.BreakGlassStatus;
import com.orderpilot.domain.incident.IncidentAlertRecord;
import com.orderpilot.domain.incident.IncidentAlertRecordRepository;
import com.orderpilot.domain.incident.IncidentRecord;
import com.orderpilot.domain.incident.IncidentRecordRepository;
import com.orderpilot.domain.incident.IncidentSeverity;
import com.orderpilot.domain.incident.IncidentType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-53 — the single authority for the controlled emergency incident-response foundation. It owns the
 * incident lifecycle and the break-glass request/approval/revocation lifecycle, and it authorizes a usable
 * break-glass grant at runtime. It is read-only with respect to business truth: it only ever reads/writes
 * incident, break-glass, and record-only alert rows plus audit events. It NEVER mutates an
 * order/quote/inventory/customer/price row, NEVER runs SQL/script, and NEVER calls a connector/ERP.
 *
 * <p>Core law enforced here:
 * <ul>
 *   <li>break-glass requires an incident, a reason, a scope, a tenant scope, and a bounded TTL;</li>
 *   <li>a break-glass request is unusable until a SEPARATE approver approves it (no self-approval);</li>
 *   <li>break-glass always expires; an expired/rejected/revoked request can never authorize;</li>
 *   <li>a closed incident can never receive a new approved break-glass grant;</li>
 *   <li>every transition is audited; a denial emits a safe denial audit and a generic 403.</li>
 * </ul>
 */
@Service
public class IncidentResponseService {
  /** Hard ceiling on a break-glass TTL — emergency access is short-lived and always expires. */
  public static final Duration MAX_BREAK_GLASS_TTL = Duration.ofHours(4);

  private static final String ENTITY_INCIDENT = "INCIDENT_RECORD";
  private static final String ENTITY_BREAK_GLASS = "BREAK_GLASS_ACCESS_REQUEST";
  private static final String ENTITY_ALERT = "INCIDENT_ALERT_RECORD";

  private final IncidentRecordRepository incidentRepository;
  private final BreakGlassAccessRequestRepository breakGlassRepository;
  private final IncidentAlertRecordRepository alertRepository;
  private final AuditEventService auditEventService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public IncidentResponseService(
      IncidentRecordRepository incidentRepository,
      BreakGlassAccessRequestRepository breakGlassRepository,
      IncidentAlertRecordRepository alertRepository,
      AuditEventService auditEventService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.incidentRepository = incidentRepository;
    this.breakGlassRepository = breakGlassRepository;
    this.alertRepository = alertRepository;
    this.auditEventService = auditEventService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /** Trusted break-glass session — backend-owned, only ever returned by a successful {@link #authorize}. */
  public record BreakGlassSession(
      UUID staffActor, UUID tenantId, BreakGlassScope scope, UUID requestId, UUID incidentId) {}

  // --- incident lifecycle ---

  /**
   * Create an audit-backed incident for the trusted tenant context. Title and reason are required; severity
   * and type are bounded enums (unknown values fail closed). A CRITICAL incident additionally records a
   * (record-only) critical alert. Mutates no business row.
   */
  @Transactional
  public IncidentRecord createIncident(
      UUID tenantId,
      UUID staffActor,
      String titleRaw,
      String reasonRaw,
      String severityRaw,
      String incidentTypeRaw) {
    String title = require(titleRaw, "title", IncidentRecord.MAX_TITLE_LENGTH);
    String reason = require(reasonRaw, "reason", IncidentRecord.MAX_REASON_LENGTH);
    IncidentSeverity severity = parseSeverity(severityRaw);
    IncidentType incidentType = parseType(incidentTypeRaw);
    Instant now = clock.instant();

    IncidentRecord incident = incidentRepository.save(
        new IncidentRecord(tenantId, title, reason, severity, incidentType, staffActor, now));

    audit("INCIDENT_CREATED", ENTITY_INCIDENT, incident.getId().toString(), staffActor, map -> {
      map.put("severity", severity.name());
      map.put("incidentType", incidentType.name());
      map.put("status", incident.getStatus().name());
      map.put("tenantId", tenantId == null ? "PLATFORM" : tenantId.toString());
    });

    if (severity.isCritical()) {
      recordAlert(incident, null, AlertType.CRITICAL_INCIDENT_CREATED, staffActor,
          "Critical incident created", now);
    }
    return incident;
  }

  /**
   * Close an incident. A CRITICAL incident requires a non-blank closure reason — it can never be silently
   * closed. Closing an already-closed incident is a conflict. Mutates no business row.
   */
  @Transactional
  public IncidentRecord closeIncident(UUID tenantId, UUID staffActor, UUID incidentId, String closureReasonRaw) {
    IncidentRecord incident = requireIncident(tenantId, incidentId);
    if (incident.isClosed()) {
      throw new ConflictException("Incident is already closed");
    }
    String closureReason = closureReasonRaw == null ? "" : closureReasonRaw.trim();
    if (incident.isCritical() && closureReason.isEmpty()) {
      throw new IllegalArgumentException("A critical incident requires a closure reason");
    }
    if (closureReason.length() > IncidentRecord.MAX_CLOSURE_REASON_LENGTH) {
      throw new IllegalArgumentException("closureReason exceeds maximum length");
    }
    Instant now = clock.instant();
    incident.close(closureReason.isEmpty() ? null : closureReason, now);
    incidentRepository.save(incident);

    audit("INCIDENT_CLOSED", ENTITY_INCIDENT, incident.getId().toString(), staffActor, map -> {
      map.put("severity", incident.getSeverity().name());
      map.put("status", incident.getStatus().name());
      map.put("tenantId", tenantId == null ? "PLATFORM" : tenantId.toString());
    });
    return incident;
  }

  @Transactional(readOnly = true)
  public IncidentRecord getIncident(UUID tenantId, UUID incidentId) {
    return requireIncident(tenantId, incidentId);
  }

  // --- break-glass lifecycle ---

  /**
   * Request emergency break-glass access against an OPEN incident. Requires a reason, a bounded scope, and a
   * positive TTL no greater than {@link #MAX_BREAK_GLASS_TTL}. The request is born REQUESTED (unusable) and a
   * (record-only) alert is recorded. Mutates no business row.
   */
  @Transactional
  public BreakGlassAccessRequest requestBreakGlass(
      UUID tenantId,
      UUID staffActor,
      UUID incidentId,
      String scopeRaw,
      String reasonRaw,
      Duration ttl) {
    String reason = require(reasonRaw, "reason", BreakGlassAccessRequest.MAX_REASON_LENGTH);
    BreakGlassScope scope = parseScope(scopeRaw);
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    if (ttl.compareTo(MAX_BREAK_GLASS_TTL) > 0) {
      throw new IllegalArgumentException("ttl exceeds the maximum break-glass lifetime");
    }
    IncidentRecord incident = requireIncident(tenantId, incidentId);
    if (incident.isClosed()) {
      throw new ConflictException("Cannot request break-glass against a closed incident");
    }
    Instant now = clock.instant();
    BreakGlassAccessRequest request = breakGlassRepository.save(new BreakGlassAccessRequest(
        tenantId, incidentId, staffActor, scope, reason, now, now.plus(ttl)));

    audit("BREAK_GLASS_REQUESTED", ENTITY_BREAK_GLASS, request.getId().toString(), staffActor, map -> {
      map.put("scope", scope.name());
      map.put("incidentId", incidentId.toString());
      map.put("tenantId", tenantId == null ? "PLATFORM" : tenantId.toString());
      map.put("expiresAt", request.getExpiresAt().toString());
    });
    recordAlert(incident, request, AlertType.BREAK_GLASS_REQUESTED, staffActor,
        "Break-glass requested", now);
    return request;
  }

  /**
   * Approve a pending break-glass request. The approver is backend-resolved and must DIFFER from the
   * requester (separation of duties). A closed incident can never receive a new approved grant. Approving a
   * non-pending request is a conflict. Approval alone grants no business mutation.
   */
  @Transactional
  public BreakGlassAccessRequest approveBreakGlass(
      UUID tenantId, UUID approverId, UUID requestId, String note) {
    BreakGlassAccessRequest request = requireBreakGlass(tenantId, requestId);
    if (request.getStatus() != BreakGlassStatus.REQUESTED) {
      throw new ConflictException("Break-glass request is not pending approval");
    }
    if (approverId != null && approverId.equals(request.getRequestedByStaffActor())) {
      auditDenied("BREAK_GLASS_APPROVAL_DENIED", request.getId().toString(), approverId, tenantId,
          "SELF_APPROVAL_FORBIDDEN");
      throw new SupportAccessDeniedException("Break-glass access denied");
    }
    IncidentRecord incident = requireIncident(tenantId, request.getIncidentId());
    if (incident.isClosed()) {
      throw new ConflictException("Cannot approve break-glass for a closed incident");
    }
    Instant now = clock.instant();
    request.approve(approverId, now);
    breakGlassRepository.save(request);

    audit("BREAK_GLASS_APPROVED", ENTITY_BREAK_GLASS, request.getId().toString(), approverId, map -> {
      map.put("decision", "APPROVED");
      map.put("scope", request.getScope().name());
      map.put("incidentId", request.getIncidentId().toString());
      map.put("tenantId", tenantId == null ? "PLATFORM" : tenantId.toString());
    });
    recordAlert(incident, request, AlertType.BREAK_GLASS_APPROVED, approverId, "Break-glass approved", now);
    return request;
  }

  /** Reject a pending break-glass request; a rejected request can never authorize access. */
  @Transactional
  public BreakGlassAccessRequest rejectBreakGlass(
      UUID tenantId, UUID approverId, UUID requestId, String note) {
    BreakGlassAccessRequest request = requireBreakGlass(tenantId, requestId);
    if (request.getStatus() != BreakGlassStatus.REQUESTED) {
      throw new ConflictException("Break-glass request is not pending approval");
    }
    String reason = normalizeReason(note, BreakGlassAccessRequest.MAX_REVOCATION_REASON_LENGTH);
    Instant now = clock.instant();
    request.reject(approverId, reason, now);
    breakGlassRepository.save(request);

    audit("BREAK_GLASS_REJECTED", ENTITY_BREAK_GLASS, request.getId().toString(), approverId, map -> {
      map.put("decision", "REJECTED");
      map.put("scope", request.getScope().name());
      map.put("incidentId", request.getIncidentId().toString());
      map.put("tenantId", tenantId == null ? "PLATFORM" : tenantId.toString());
    });
    recordAlert(requireIncident(tenantId, request.getIncidentId()), request,
        AlertType.BREAK_GLASS_REJECTED, approverId, "Break-glass rejected", now);
    return request;
  }

  /**
   * Revoke a break-glass request. Idempotent (first-write-wins): an already terminal request is untouched.
   * Revocation immediately denies any future {@link #authorize}.
   */
  @Transactional
  public BreakGlassAccessRequest revokeBreakGlass(
      UUID tenantId, UUID actorId, UUID requestId, String revocationReasonRaw) {
    BreakGlassAccessRequest request = requireBreakGlass(tenantId, requestId);
    String reason = normalizeReason(revocationReasonRaw, BreakGlassAccessRequest.MAX_REVOCATION_REASON_LENGTH);
    Instant now = clock.instant();
    request.revoke(reason, now);
    breakGlassRepository.save(request);

    audit("BREAK_GLASS_REVOKED", ENTITY_BREAK_GLASS, request.getId().toString(), actorId, map -> {
      map.put("scope", request.getScope().name());
      map.put("incidentId", request.getIncidentId().toString());
      map.put("tenantId", tenantId == null ? "PLATFORM" : tenantId.toString());
      map.put("status", request.getStatus().name());
    });
    recordAlert(requireIncident(tenantId, request.getIncidentId()), request,
        AlertType.BREAK_GLASS_REVOKED, actorId, "Break-glass revoked", now);
    return request;
  }

  /**
   * Runtime authorization of a usable break-glass grant. Fail-closed on EVERY mismatch — unknown request,
   * wrong tenant, wrong scope, wrong staff actor, non-approved status, expired grant, or a non-open incident
   * — emitting a generic safe denial audit and a {@link SupportAccessDeniedException} that never reveals
   * which condition failed. On success it returns a {@link BreakGlassSession} and mutates NO business row.
   *
   * <p>If an APPROVED grant is observed past its expiry, it is transitioned to EXPIRED and a (record-only)
   * expiry alert is recorded before the denial.
   */
  @Transactional
  public BreakGlassSession authorize(
      UUID tenantId, UUID staffActor, BreakGlassScope scope, UUID requestId) {
    Instant now = clock.instant();
    BreakGlassAccessRequest request = requestId == null
        ? null
        : breakGlassRepository.findByIdAndTenantId(requestId, tenantId).orElse(null);
    if (request == null) {
      denied(staffActor, tenantId, scope, requestId, "NO_BREAK_GLASS_REQUEST");
    }
    if (request.markExpiredIfElapsed(now)) {
      breakGlassRepository.save(request);
      recordAlert(requireIncident(tenantId, request.getIncidentId()), request,
          AlertType.BREAK_GLASS_EXPIRED, staffActor, "Break-glass expired", now);
      denied(staffActor, tenantId, scope, requestId, "EXPIRED");
    }
    if (!request.isApproved()) {
      denied(staffActor, tenantId, scope, requestId, "NOT_APPROVED");
    }
    if (request.getScope() != scope) {
      denied(staffActor, tenantId, scope, requestId, "SCOPE_MISMATCH");
    }
    if (staffActor == null || !staffActor.equals(request.getRequestedByStaffActor())) {
      denied(staffActor, tenantId, scope, requestId, "ACTOR_MISMATCH");
    }
    if (request.isExpired(now)) {
      denied(staffActor, tenantId, scope, requestId, "EXPIRED");
    }
    IncidentRecord incident = requireIncident(tenantId, request.getIncidentId());
    if (incident.isClosed()) {
      denied(staffActor, tenantId, scope, requestId, "INCIDENT_NOT_OPEN");
    }
    audit("BREAK_GLASS_AUTHORIZATION_GRANTED", ENTITY_BREAK_GLASS, request.getId().toString(), staffActor,
        map -> {
          map.put("decision", "ALLOWED");
          map.put("scope", scope.name());
          map.put("incidentId", request.getIncidentId().toString());
          map.put("tenantId", tenantId == null ? "PLATFORM" : tenantId.toString());
        });
    return new BreakGlassSession(staffActor, tenantId, scope, request.getId(), request.getIncidentId());
  }

  // --- helpers ---

  private void denied(UUID staffActor, UUID tenantId, BreakGlassScope scope, UUID requestId, String reasonCode) {
    audit("BREAK_GLASS_AUTHORIZATION_DENIED", ENTITY_BREAK_GLASS,
        requestId == null ? "n/a" : requestId.toString(), staffActor, map -> {
          map.put("decision", "DENIED");
          map.put("reasonCode", reasonCode);
          map.put("scope", scope == null ? "UNKNOWN" : scope.name());
          map.put("tenantId", tenantId == null ? "UNKNOWN" : tenantId.toString());
        });
    throw new SupportAccessDeniedException("Break-glass access denied");
  }

  private void auditDenied(String action, String entityId, UUID actorId, UUID tenantId, String reasonCode) {
    audit(action, ENTITY_BREAK_GLASS, entityId, actorId, map -> {
      map.put("decision", "DENIED");
      map.put("reasonCode", reasonCode);
      map.put("tenantId", tenantId == null ? "UNKNOWN" : tenantId.toString());
    });
  }

  private void recordAlert(
      IncidentRecord incident,
      BreakGlassAccessRequest request,
      AlertType alertType,
      UUID staffActor,
      String detail,
      Instant now) {
    IncidentAlertRecord alert = alertRepository.save(new IncidentAlertRecord(
        incident.getTenantId(),
        incident.getId(),
        request == null ? null : request.getId(),
        alertType,
        detail,
        now));
    audit("INCIDENT_ALERT_RECORDED", ENTITY_ALERT, alert.getId().toString(), staffActor, map -> {
      map.put("alertType", alertType.name());
      map.put("incidentId", incident.getId().toString());
      map.put("status", alert.getStatus().name());
    });
  }

  private IncidentRecord requireIncident(UUID tenantId, UUID incidentId) {
    if (incidentId == null) {
      throw new IllegalArgumentException("incidentId is required");
    }
    return incidentRepository.findByIdAndTenantId(incidentId, tenantId)
        .orElseThrow(() -> new NotFoundException("Incident not found"));
  }

  private BreakGlassAccessRequest requireBreakGlass(UUID tenantId, UUID requestId) {
    if (requestId == null) {
      throw new IllegalArgumentException("requestId is required");
    }
    return breakGlassRepository.findByIdAndTenantId(requestId, tenantId)
        .orElseThrow(() -> new NotFoundException("Break-glass request not found"));
  }

  private static String require(String raw, String field, int maxLength) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(field + " exceeds maximum length");
    }
    return value;
  }

  private static String normalizeReason(String raw, int maxLength) {
    String value = raw == null ? null : raw.trim();
    if (value == null || value.isEmpty()) {
      return null;
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException("reason exceeds maximum length");
    }
    return value;
  }

  private static IncidentSeverity parseSeverity(String raw) {
    return parseEnum(IncidentSeverity.class, raw, "severity");
  }

  private static IncidentType parseType(String raw) {
    return parseEnum(IncidentType.class, raw, "incidentType");
  }

  private static BreakGlassScope parseScope(String raw) {
    return parseEnum(BreakGlassScope.class, raw, "scope");
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown " + field + ": " + raw);
    }
  }

  private void audit(
      String action,
      String entityType,
      String entityId,
      UUID actorId,
      java.util.function.Consumer<Map<String, Object>> builder) {
    auditEventService.record(action, entityType, entityId, actorId, metadata(builder));
  }

  private String metadata(java.util.function.Consumer<Map<String, Object>> builder) {
    Map<String, Object> map = new LinkedHashMap<>();
    builder.accept(map);
    try {
      return objectMapper.writeValueAsString(map);
    } catch (Exception ex) {
      return "{}";
    }
  }
}
