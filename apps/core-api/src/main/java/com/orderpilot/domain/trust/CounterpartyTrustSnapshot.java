package com.orderpilot.domain.trust;

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
 * OP-CAP-17B Counterparty Trust Profile Foundation.
 *
 * Append-only audit/evidence snapshot of a counterparty's trust state at the moment a trust decision
 * updated the profile. Never mutated after creation. {@code reasonSummary} is bounded and generic —
 * never raw document text or sensitive values.
 */
@Entity
@Table(name = "counterparty_trust_snapshot")
public class CounterpartyTrustSnapshot {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "customer_account_id", nullable = false) private UUID customerAccountId;

  @Column(name = "profile_id", nullable = false) private UUID profileId;

  @Column(name = "trust_score", nullable = false) private int trustScore;

  @Enumerated(EnumType.STRING)
  @Column(name = "trust_tier", nullable = false, length = 16) private TrustTier trustTier;

  @Enumerated(EnumType.STRING)
  @Column(name = "risk_level", length = 16) private TrustRiskLevel riskLevel;

  @Column(name = "reason_summary", length = 280) private String reasonSummary;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 32) private CounterpartyTrustSourceType sourceType;

  @Column(name = "source_ref_id") private UUID sourceRefId;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected CounterpartyTrustSnapshot() {}

  public CounterpartyTrustSnapshot(UUID tenantId, UUID customerAccountId, UUID profileId, int trustScore,
      TrustTier trustTier, TrustRiskLevel riskLevel, String reasonSummary,
      CounterpartyTrustSourceType sourceType, UUID sourceRefId, Instant createdAt) {
    this.tenantId = tenantId;
    this.customerAccountId = customerAccountId;
    this.profileId = profileId;
    this.trustScore = trustScore;
    this.trustTier = trustTier;
    this.riskLevel = riskLevel;
    this.reasonSummary = reasonSummary;
    this.sourceType = sourceType;
    this.sourceRefId = sourceRefId;
    this.createdAt = createdAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public UUID getProfileId() { return profileId; }
  public int getTrustScore() { return trustScore; }
  public TrustTier getTrustTier() { return trustTier; }
  public TrustRiskLevel getRiskLevel() { return riskLevel; }
  public String getReasonSummary() { return reasonSummary; }
  public CounterpartyTrustSourceType getSourceType() { return sourceType; }
  public UUID getSourceRefId() { return sourceRefId; }
  public Instant getCreatedAt() { return createdAt; }
}
