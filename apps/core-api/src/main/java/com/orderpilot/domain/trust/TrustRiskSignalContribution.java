package com.orderpilot.domain.trust;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-17D Trust Risk Decision Engine.
 *
 * One normalized, explainable contribution to a {@link TrustRiskDecision}. Preserves WHY a decision
 * exists — never a single opaque JSON blob. Carries the source subsystem, the deterministic reason
 * code, its severity, weight, the resulting {@code contributionScore}, and an optional {@code forcedLevel}
 * floor. Holds bounded metadata only — never raw document text, OCR text, prompt text, account numbers,
 * or bank credentials.
 */
@Entity
@Table(name = "trust_risk_signal_contribution")
public class TrustRiskSignalContribution {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "trust_risk_decision_id", nullable = false) private UUID trustRiskDecisionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 24) private TrustRiskSignalSourceType sourceType;

  /** Optional id of the upstream evidence (trust run id, obligation id, counterparty id, ...). */
  @Column(name = "source_id") private UUID sourceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "signal_code", nullable = false, length = 48) private TrustRiskReasonCode signalCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 16) private TrustRiskLevel severity;

  /** Optional confidence 0..1; null when not applicable. */
  @Column(name = "confidence", precision = 5, scale = 4) private BigDecimal confidence;

  @Column(name = "weight", nullable = false) private int weight;

  @Column(name = "contribution_score", nullable = false) private int contributionScore;

  @Enumerated(EnumType.STRING)
  @Column(name = "forced_level", length = 16) private TrustRiskLevel forcedLevel;

  /** Bounded, generic explanation. Never raw document/OCR/prompt text or extracted values. */
  @Column(name = "explanation", length = 280) private String explanation;

  /** Bounded metadata reference pointer. Never raw content. */
  @Column(name = "evidence_ref", length = 120) private String evidenceRef;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected TrustRiskSignalContribution() {}

  public TrustRiskSignalContribution(UUID tenantId, UUID trustRiskDecisionId,
      TrustRiskSignalSourceType sourceType, UUID sourceId, TrustRiskReasonCode signalCode,
      TrustRiskLevel severity, BigDecimal confidence, int weight, int contributionScore,
      TrustRiskLevel forcedLevel, String explanation, String evidenceRef, Instant createdAt) {
    this.tenantId = tenantId;
    this.trustRiskDecisionId = trustRiskDecisionId;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.signalCode = signalCode;
    this.severity = severity;
    this.confidence = confidence;
    this.weight = weight;
    this.contributionScore = contributionScore;
    this.forcedLevel = forcedLevel;
    this.explanation = explanation;
    this.evidenceRef = evidenceRef;
    this.createdAt = createdAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getTrustRiskDecisionId() { return trustRiskDecisionId; }
  public TrustRiskSignalSourceType getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public TrustRiskReasonCode getSignalCode() { return signalCode; }
  public TrustRiskLevel getSeverity() { return severity; }
  public BigDecimal getConfidence() { return confidence; }
  public int getWeight() { return weight; }
  public int getContributionScore() { return contributionScore; }
  public TrustRiskLevel getForcedLevel() { return forcedLevel; }
  public String getExplanation() { return explanation; }
  public String getEvidenceRef() { return evidenceRef; }
  public Instant getCreatedAt() { return createdAt; }
}
