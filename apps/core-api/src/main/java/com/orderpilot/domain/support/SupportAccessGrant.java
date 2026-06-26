package com.orderpilot.domain.support;

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
 * OP-CAP-51 — a scoped, reasoned, expiring authorization for one staff principal to support exactly one
 * tenant for exactly one {@link StaffSupportScope}. This is the core of the support safety model:
 *
 * <ul>
 *   <li>a support case reference / reason is required (no anonymous access);</li>
 *   <li>the tenant scope is required and is the sole tenant this grant ever authorizes;</li>
 *   <li>{@code expiresAt} is mandatory and enforced deterministically — everything expires;</li>
 *   <li>a grant is usable only while {@code status == ACTIVE} and strictly before {@code expiresAt};</li>
 *   <li>there is no "all tenants" / permanent grant representation by construction.</li>
 * </ul>
 *
 * <p>The backend owns {@code status}, {@code expiresAt}, {@code createdAt}, and {@code createdBy}; a client
 * never supplies them.
 */
@Entity
@Table(name = "support_access_grant")
public class SupportAccessGrant {
  public enum Status {
    ACTIVE,
    REVOKED
  }

  /** Upper bound for the operator-supplied support case reference / reason (matches the V62 column width). */
  public static final int MAX_SUPPORT_CASE_REF_LENGTH = 200;

  @Id @GeneratedValue private UUID id;
  @Column(name = "staff_user_id", nullable = false) private UUID staffUserId;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Enumerated(EnumType.STRING)
  @Column(name = "scope", nullable = false, length = 40) private StaffSupportScope scope;
  @Column(name = "support_case_ref", nullable = false, length = MAX_SUPPORT_CASE_REF_LENGTH) private String supportCaseRef;
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20) private Status status;
  @Column(name = "expires_at", nullable = false) private Instant expiresAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "created_by") private UUID createdBy;
  @Column(name = "revoked_at") private Instant revokedAt;
  @Column(name = "revoked_by") private UUID revokedBy;

  protected SupportAccessGrant() {}

  public SupportAccessGrant(
      UUID staffUserId,
      UUID tenantId,
      StaffSupportScope scope,
      String supportCaseRef,
      Instant expiresAt,
      UUID createdBy,
      Instant now) {
    this.staffUserId = staffUserId;
    this.tenantId = tenantId;
    this.scope = scope;
    this.supportCaseRef = supportCaseRef;
    this.status = Status.ACTIVE;
    this.expiresAt = expiresAt;
    this.createdBy = createdBy;
    this.createdAt = now;
  }

  /** Deterministic expiry: a grant is valid only strictly before {@code expiresAt}. */
  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public boolean isRevoked() {
    return status == Status.REVOKED;
  }

  /** Usable only while ACTIVE and not yet expired. */
  public boolean isUsable(Instant now) {
    return status == Status.ACTIVE && !isExpired(now);
  }

  /** First-write-wins revoke: an already-revoked grant is left untouched (idempotent). */
  public void revoke(UUID actorId, Instant now) {
    if (isRevoked()) {
      return;
    }
    this.status = Status.REVOKED;
    this.revokedAt = now;
    this.revokedBy = actorId;
  }

  public UUID getId() { return id; }
  public UUID getStaffUserId() { return staffUserId; }
  public UUID getTenantId() { return tenantId; }
  public StaffSupportScope getScope() { return scope; }
  public String getSupportCaseRef() { return supportCaseRef; }
  public Status getStatus() { return status; }
  public Instant getExpiresAt() { return expiresAt; }
  public Instant getCreatedAt() { return createdAt; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getRevokedAt() { return revokedAt; }
  public UUID getRevokedBy() { return revokedBy; }
}
