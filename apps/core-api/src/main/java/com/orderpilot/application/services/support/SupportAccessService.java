package com.orderpilot.application.services.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.support.StaffSupportScope;
import com.orderpilot.domain.support.StaffUser;
import com.orderpilot.domain.support.StaffUserRepository;
import com.orderpilot.domain.support.SupportAccessGrant;
import com.orderpilot.domain.support.SupportAccessGrantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-51 — the single authority for internal support access. It validates the staff principal, the
 * staff role scope, and a tenant-scoped, unexpired {@link SupportAccessGrant}, and it emits an audit event
 * for <b>every</b> decision (allow or deny). It is read-only with respect to business truth: it only ever
 * reads staff/grant rows and writes audit + the grant lifecycle it owns.
 *
 * <p>Fail-closed: any missing/expired/wrong-tenant/wrong-scope/unknown-principal condition results in a
 * generic {@link SupportAccessDeniedException} (no side effect beyond a safe denial audit) and never leaks
 * which condition failed.
 */
@Service
public class SupportAccessService {
  /** Hard ceiling on a grant TTL — everything expires, and no grant may be longer-lived than this. */
  public static final Duration MAX_GRANT_TTL = Duration.ofHours(24);

  private static final String ENTITY_GRANT = "SUPPORT_ACCESS_GRANT";
  private static final String ENTITY_ACCESS = "SUPPORT_ACCESS";

  private final StaffUserRepository staffUserRepository;
  private final SupportAccessGrantRepository grantRepository;
  private final AuditEventService auditEventService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public SupportAccessService(
      StaffUserRepository staffUserRepository,
      SupportAccessGrantRepository grantRepository,
      AuditEventService auditEventService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.staffUserRepository = staffUserRepository;
    this.grantRepository = grantRepository;
    this.auditEventService = auditEventService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /** Trusted support session result — backend-owned, never built from a request body. */
  public record SupportSession(UUID staffUserId, UUID tenantId, StaffSupportScope scope, UUID grantId) {}

  /**
   * Validate that {@code staffUserId} may act with {@code scope} against {@code tenantId} right now. Emits
   * an allow or deny audit and either returns a {@link SupportSession} or throws
   * {@link SupportAccessDeniedException}.
   */
  @Transactional
  public SupportSession authorize(UUID staffUserId, UUID tenantId, StaffSupportScope scope) {
    Instant now = clock.instant();
    StaffUser staff = staffUserId == null ? null : staffUserRepository.findById(staffUserId).orElse(null);
    if (staff == null || !staff.isActive()) {
      denied(staffUserId, tenantId, scope, "PRINCIPAL_UNKNOWN_OR_DISABLED");
    }
    if (!staff.permits(scope)) {
      denied(staffUserId, tenantId, scope, "SCOPE_NOT_PERMITTED_FOR_ROLE");
    }
    List<SupportAccessGrant> grants = grantRepository
        .findByStaffUserIdAndTenantIdAndScopeAndStatusOrderByExpiresAtDesc(
            staffUserId, tenantId, scope, SupportAccessGrant.Status.ACTIVE);
    SupportAccessGrant usable = grants.stream().filter(g -> g.isUsable(now)).findFirst().orElse(null);
    if (usable == null) {
      denied(staffUserId, tenantId, scope, "NO_ACTIVE_GRANT");
    }
    audit("SUPPORT_ACCESS_GRANTED", ENTITY_ACCESS, usable.getId().toString(), staffUserId, metadata(map -> {
      map.put("decision", "ALLOWED");
      map.put("scope", scope.name());
      map.put("tenantId", tenantId.toString());
      map.put("grantId", usable.getId().toString());
    }));
    return new SupportSession(staffUserId, tenantId, scope, usable.getId());
  }

  /**
   * Create a scoped, reasoned, expiring grant for a known staff principal. The backend owns status,
   * expiry, creation time, and the creating actor; the client supplies only business intent.
   */
  @Transactional
  public SupportAccessGrant createGrant(
      UUID granteeStaffUserId,
      UUID tenantId,
      StaffSupportScope scope,
      String supportCaseRef,
      Duration ttl,
      UUID createdBy) {
    if (granteeStaffUserId == null) {
      throw new IllegalArgumentException("granteeStaffUserId is required");
    }
    if (scope == null) {
      throw new IllegalArgumentException("scope is required");
    }
    String caseRef = supportCaseRef == null ? "" : supportCaseRef.trim();
    if (caseRef.isEmpty()) {
      throw new IllegalArgumentException("supportCaseRef (reason) is required");
    }
    if (caseRef.length() > SupportAccessGrant.MAX_SUPPORT_CASE_REF_LENGTH) {
      throw new IllegalArgumentException("supportCaseRef exceeds maximum length");
    }
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    if (ttl.compareTo(MAX_GRANT_TTL) > 0) {
      throw new IllegalArgumentException("ttl exceeds the maximum support grant lifetime");
    }
    StaffUser grantee = staffUserRepository.findById(granteeStaffUserId).orElse(null);
    if (grantee == null || !grantee.isActive()) {
      throw new IllegalArgumentException("Unknown or disabled staff user");
    }
    if (!grantee.getRole().permits(scope)) {
      throw new IllegalArgumentException("Staff role does not permit the requested scope");
    }
    Instant now = clock.instant();
    SupportAccessGrant grant = grantRepository.save(new SupportAccessGrant(
        granteeStaffUserId, tenantId, scope, caseRef, now.plus(ttl), createdBy, now));
    audit("SUPPORT_ACCESS_GRANT_CREATED", ENTITY_GRANT, grant.getId().toString(), createdBy, metadata(map -> {
      map.put("granteeStaffUserId", granteeStaffUserId.toString());
      map.put("scope", scope.name());
      map.put("tenantId", tenantId.toString());
      map.put("approvalStatus", grant.getApprovalStatus().name());
      map.put("expiresAt", grant.getExpiresAt().toString());
    }));
    // OP-CAP-52: a sensitive scope is born PENDING_APPROVAL — record that an approval request now exists so
    // the gate is auditable even before any approver acts. A low-risk grant is auto-approved (no request).
    if (grant.isPendingApproval()) {
      audit("SUPPORT_GRANT_APPROVAL_REQUESTED", ENTITY_GRANT, grant.getId().toString(), createdBy, metadata(map -> {
        map.put("granteeStaffUserId", granteeStaffUserId.toString());
        map.put("scope", scope.name());
        map.put("tenantId", tenantId.toString());
      }));
    }
    return grant;
  }

  /**
   * OP-CAP-52 — approve a pending sensitive grant. The approver is backend-resolved (never from the body),
   * the grant is found only within its own tenant, and an approver may not approve their own grant request
   * (separation of duties). Approving a non-pending grant is a conflict. Emits an approval audit.
   */
  @Transactional
  public SupportAccessGrant approveGrant(UUID grantId, UUID tenantId, UUID approverId, String note) {
    return decideGrant(grantId, tenantId, approverId, note, true);
  }

  /** OP-CAP-52 — reject a pending sensitive grant; a rejected grant can never authorize access. */
  @Transactional
  public SupportAccessGrant rejectGrant(UUID grantId, UUID tenantId, UUID approverId, String note) {
    return decideGrant(grantId, tenantId, approverId, note, false);
  }

  private SupportAccessGrant decideGrant(
      UUID grantId, UUID tenantId, UUID approverId, String noteRaw, boolean approve) {
    SupportAccessGrant grant = grantRepository.findByIdAndTenantId(grantId, tenantId)
        .orElseThrow(() -> new NotFoundException("Support access grant not found"));
    if (!grant.isPendingApproval()) {
      throw new ConflictException("Support access grant is not pending approval");
    }
    // Separation of duties: the actor who created the grant request cannot also approve/reject it.
    if (approverId != null && approverId.equals(grant.getCreatedBy())) {
      audit("SUPPORT_GRANT_APPROVAL_DENIED", ENTITY_GRANT, grant.getId().toString(), approverId, metadata(map -> {
        map.put("decision", "DENIED");
        map.put("reasonCode", "SELF_APPROVAL_FORBIDDEN");
        map.put("tenantId", tenantId.toString());
      }));
      throw new SupportAccessDeniedException("Support access denied");
    }
    String note = normalizeNote(noteRaw);
    Instant now = clock.instant();
    if (approve) {
      grant.approve(approverId, note, now);
    } else {
      grant.reject(approverId, note, now);
    }
    grantRepository.save(grant);
    audit(approve ? "SUPPORT_GRANT_APPROVED" : "SUPPORT_GRANT_REJECTED",
        ENTITY_GRANT, grant.getId().toString(), approverId, metadata(map -> {
          map.put("decision", approve ? "APPROVED" : "REJECTED");
          map.put("scope", grant.getScope().name());
          map.put("tenantId", tenantId.toString());
        }));
    return grant;
  }

  private static String normalizeNote(String raw) {
    String note = raw == null ? null : raw.trim();
    if (note == null || note.isEmpty()) {
      return null;
    }
    if (note.length() > SupportAccessGrant.MAX_DECISION_NOTE_LENGTH) {
      throw new IllegalArgumentException("decisionNote exceeds maximum length");
    }
    return note;
  }

  /** Revoke a tenant-scoped grant. Idempotent; never found across tenants. */
  @Transactional
  public SupportAccessGrant revokeGrant(UUID grantId, UUID tenantId, UUID actorId) {
    SupportAccessGrant grant = grantRepository.findByIdAndTenantId(grantId, tenantId)
        .orElseThrow(() -> new NotFoundException("Support access grant not found"));
    grant.revoke(actorId, clock.instant());
    grantRepository.save(grant);
    audit("SUPPORT_ACCESS_GRANT_REVOKED", ENTITY_GRANT, grant.getId().toString(), actorId, metadata(map -> {
      map.put("scope", grant.getScope().name());
      map.put("tenantId", tenantId.toString());
    }));
    return grant;
  }

  /** Tenant-scoped registry listing for the support console (read-only, safe metadata). */
  @Transactional(readOnly = true)
  public List<SupportAccessGrant> listGrants(UUID tenantId) {
    return grantRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
  }

  private void denied(UUID staffUserId, UUID tenantId, StaffSupportScope scope, String reasonCode) {
    audit("SUPPORT_ACCESS_DENIED", ENTITY_ACCESS, "n/a", staffUserId, metadata(map -> {
      map.put("decision", "DENIED");
      map.put("reasonCode", reasonCode);
      map.put("scope", scope == null ? "UNKNOWN" : scope.name());
      map.put("tenantId", tenantId == null ? "UNKNOWN" : tenantId.toString());
    }));
    throw new SupportAccessDeniedException("Support access denied");
  }

  private void audit(String action, String entityType, String entityId, UUID actorId, String metadataJson) {
    auditEventService.record(action, entityType, entityId, actorId, metadataJson);
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
