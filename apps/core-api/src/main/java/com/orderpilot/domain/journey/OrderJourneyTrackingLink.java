package com.orderpilot.domain.journey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-46C — a tokenized, tenant- and journey-scoped, expiring secure tracking link.
 *
 * <p>Only the SHA-256 hash of the token is stored ({@code tokenHash}); the raw token is shown to the
 * operator exactly once at creation and is never persisted or logged. The link's authority is its own
 * row: the tenant/journey scope is read from here, never from the request. Resolution is read-only —
 * this row carries no business state of the journey and is never used to mutate one.
 *
 * <p>OP-CAP-46G — a link may be explicitly revoked by an operator before its natural expiry. Revocation
 * stores only safe metadata on this row ({@code revokedAt}, the optional trusted {@code revokedBy} actor
 * id, and an optional bounded operator-only {@code revocationReason}); it never touches the journey,
 * milestones, signals, or ETA. A revoked link is denied on public resolution exactly like an expired or
 * unknown one (same generic not-found), so the customer learns nothing about why a link stopped working.
 */
@Entity
@Table(name = "order_journey_tracking_link")
public class OrderJourneyTrackingLink {
  /** Upper bound for the operator-only revocation reason (matches the V61 column width). */
  public static final int MAX_REVOCATION_REASON_LENGTH = 280;

  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "journey_id", nullable = false) private UUID journeyId;
  @Column(name = "token_hash", nullable = false, length = 64) private String tokenHash;
  @Column(name = "expires_at", nullable = false) private Instant expiresAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "created_by") private UUID createdBy;
  @Column(name = "revoked_at") private Instant revokedAt;
  @Column(name = "revoked_by") private UUID revokedBy;
  @Column(name = "revocation_reason", length = MAX_REVOCATION_REASON_LENGTH) private String revocationReason;

  protected OrderJourneyTrackingLink() {}

  public OrderJourneyTrackingLink(UUID tenantId, UUID journeyId, String tokenHash, Instant expiresAt,
      UUID createdBy, Instant now) {
    this.tenantId = tenantId;
    this.journeyId = journeyId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
    this.createdBy = createdBy;
    this.createdAt = now;
  }

  /** Deterministic expiry check: a link is valid only strictly before {@code expiresAt}. */
  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  /** OP-CAP-46G — a link is revoked once {@code revokedAt} is stamped. */
  public boolean isRevoked() {
    return revokedAt != null;
  }

  /**
   * OP-CAP-46G — stamp this link as revoked. First-write-wins: an already-revoked link is left
   * untouched (idempotent no-op) so a repeat revoke never overwrites the original actor/time/reason.
   * The {@code reason} is expected to be pre-sanitized and length-bounded by the caller.
   */
  public void revoke(UUID actorId, String reason, Instant now) {
    if (isRevoked()) {
      return;
    }
    this.revokedAt = now;
    this.revokedBy = actorId;
    this.revocationReason = reason;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getJourneyId() { return journeyId; }
  public String getTokenHash() { return tokenHash; }
  public Instant getExpiresAt() { return expiresAt; }
  public Instant getCreatedAt() { return createdAt; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getRevokedAt() { return revokedAt; }
  public UUID getRevokedBy() { return revokedBy; }
  public String getRevocationReason() { return revocationReason; }
}
