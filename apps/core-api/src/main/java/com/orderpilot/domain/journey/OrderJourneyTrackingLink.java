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
 */
@Entity
@Table(name = "order_journey_tracking_link")
public class OrderJourneyTrackingLink {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "journey_id", nullable = false) private UUID journeyId;
  @Column(name = "token_hash", nullable = false, length = 64) private String tokenHash;
  @Column(name = "expires_at", nullable = false) private Instant expiresAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "created_by") private UUID createdBy;

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

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getJourneyId() { return journeyId; }
  public String getTokenHash() { return tokenHash; }
  public Instant getExpiresAt() { return expiresAt; }
  public Instant getCreatedAt() { return createdAt; }
  public UUID getCreatedBy() { return createdBy; }
}
