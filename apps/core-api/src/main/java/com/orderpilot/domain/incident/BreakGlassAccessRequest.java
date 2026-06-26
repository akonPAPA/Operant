package com.orderpilot.domain.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-53 — a scoped, reasoned, EXPIRING emergency break-glass access request, tied to exactly one
 * {@link IncidentRecord} and (normally) one tenant. This is the heart of the break-glass safety model:
 *
 * <ul>
 *   <li>an incident, a reason, a {@link BreakGlassScope}, and a bounded TTL are all required;</li>
 *   <li>a request is born {@code REQUESTED} and is NOT usable until a separate approver approves it;</li>
 *   <li>{@code expiresAt} is mandatory — emergency access always expires;</li>
 *   <li>a request is usable only while {@code APPROVED}, not revoked, and strictly before {@code expiresAt};</li>
 *   <li>the requester can never self-approve (enforced in the service);</li>
 *   <li>a valid break-glass request mutates NO business truth by itself — the scope is a policy label only.</li>
 * </ul>
 *
 * <p>The backend owns {@code status}, every actor field, every timestamp, and {@code expiresAt}; a client
 * never supplies them.
 */
@Entity
@Table(name = "break_glass_access_request")
public class BreakGlassAccessRequest {
  public static final int MAX_REASON_LENGTH = 1000;
  public static final int MAX_REVOCATION_REASON_LENGTH = 1000;

  @Id @GeneratedValue private UUID id;
  /** Tenant scope. Nullable is reserved for a future explicitly platform-wide incident break-glass. */
  @Column(name = "tenant_id") private UUID tenantId;
  @Column(name = "incident_id", nullable = false) private UUID incidentId;
  @Column(name = "requested_by_staff_actor") private UUID requestedByStaffActor;
  @Column(name = "approved_by_staff_actor") private UUID approvedByStaffActor;
  @Column(name = "rejected_by_staff_actor") private UUID rejectedByStaffActor;
  @Enumerated(EnumType.STRING)
  @Column(name = "scope", nullable = false, length = 40) private BreakGlassScope scope;
  @Column(name = "reason", nullable = false, length = MAX_REASON_LENGTH) private String reason;
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20) private BreakGlassStatus status;
  @Column(name = "requested_at", nullable = false) private Instant requestedAt;
  @Column(name = "decided_at") private Instant decidedAt;
  @Column(name = "expires_at", nullable = false) private Instant expiresAt;
  @Column(name = "revoked_at") private Instant revokedAt;
  @Column(name = "revocation_reason", length = MAX_REVOCATION_REASON_LENGTH) private String revocationReason;

  protected BreakGlassAccessRequest() {}

  public BreakGlassAccessRequest(
      UUID tenantId,
      UUID incidentId,
      UUID requestedByStaffActor,
      BreakGlassScope scope,
      String reason,
      Instant requestedAt,
      Instant expiresAt) {
    this.tenantId = tenantId;
    this.incidentId = incidentId;
    this.requestedByStaffActor = requestedByStaffActor;
    this.scope = scope;
    this.reason = reason;
    this.status = BreakGlassStatus.REQUESTED;
    this.requestedAt = requestedAt;
    this.expiresAt = expiresAt;
  }

  /** Deterministic expiry: usable only strictly before {@code expiresAt}. */
  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public boolean isApproved() {
    return status == BreakGlassStatus.APPROVED;
  }

  /** Usable only while APPROVED and not yet expired. Revoked/rejected/expired are never usable. */
  public boolean isUsable(Instant now) {
    return status == BreakGlassStatus.APPROVED && !isExpired(now);
  }

  /** Approve a pending request. Only a {@code REQUESTED} request can be approved. */
  public void approve(UUID approverId, Instant now) {
    if (status != BreakGlassStatus.REQUESTED) {
      throw new IllegalStateException("Break-glass request is not pending approval");
    }
    this.status = BreakGlassStatus.APPROVED;
    this.approvedByStaffActor = approverId;
    this.decidedAt = now;
  }

  /** Reject a pending request; a rejected request can never authorize access. */
  public void reject(UUID rejecterId, String reason, Instant now) {
    if (status != BreakGlassStatus.REQUESTED) {
      throw new IllegalStateException("Break-glass request is not pending approval");
    }
    this.status = BreakGlassStatus.REJECTED;
    this.rejectedByStaffActor = rejecterId;
    this.revocationReason = reason;
    this.decidedAt = now;
  }

  /**
   * Revoke a requested/approved grant. First-write-wins: an already terminal (revoked/rejected/expired)
   * request is left untouched (idempotent), so revoke never resurrects or re-decides a closed request.
   */
  public void revoke(String reason, Instant now) {
    if (status == BreakGlassStatus.REVOKED
        || status == BreakGlassStatus.REJECTED
        || status == BreakGlassStatus.EXPIRED) {
      return;
    }
    // The revoking actor is attributed through the audit event; the row keeps the revoked_at marker.
    this.status = BreakGlassStatus.REVOKED;
    this.revocationReason = reason;
    this.revokedAt = now;
  }

  /** Synchronous expiry transition: an APPROVED-but-expired grant becomes EXPIRED when observed. */
  public boolean markExpiredIfElapsed(Instant now) {
    if (status == BreakGlassStatus.APPROVED && isExpired(now)) {
      this.status = BreakGlassStatus.EXPIRED;
      return true;
    }
    return false;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getIncidentId() { return incidentId; }
  public UUID getRequestedByStaffActor() { return requestedByStaffActor; }
  public UUID getApprovedByStaffActor() { return approvedByStaffActor; }
  public UUID getRejectedByStaffActor() { return rejectedByStaffActor; }
  public BreakGlassScope getScope() { return scope; }
  public String getReason() { return reason; }
  public BreakGlassStatus getStatus() { return status; }
  public Instant getRequestedAt() { return requestedAt; }
  public Instant getDecidedAt() { return decidedAt; }
  public Instant getExpiresAt() { return expiresAt; }
  public Instant getRevokedAt() { return revokedAt; }
  public String getRevocationReason() { return revocationReason; }
}
