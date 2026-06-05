package com.orderpilot.domain.aiwork;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * OP-CAP-07A AI Agent Work Layer — an UNTRUSTED, advisory AI work suggestion.
 *
 * <p>This entity is deliberately separate from trusted business state. It records what the AI Work
 * Assistant suggested for a given source object (channel message, operator review, quote, ...). It
 * never holds or mutates quote/order/inventory/pricing/customer source-of-truth data. Accepting a
 * suggestion only records operator intent ({@link AiWorkStatus#ACCEPTED}); any real business
 * mutation must be performed afterwards through the existing typed backend command services.
 */
@Entity
@Table(name = "ai_work_suggestion")
public class AiWorkSuggestion {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "work_type", nullable = false) private String workType;
  @Column(name = "source_type", nullable = false) private String sourceType;
  @Column(name = "source_id", nullable = false) private UUID sourceId;
  @Column(name = "status", nullable = false) private String status;
  @Column(name = "strategy_version", nullable = false) private String strategyVersion;
  @Column(name = "risk_level", nullable = false) private String riskLevel;
  @Column(name = "confidence") private BigDecimal confidence;
  @Column(name = "generated_text", nullable = false) private String generatedText;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "structured_payload_json", nullable = false, columnDefinition = "jsonb")
  private String structuredPayloadJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "evidence_refs_json", nullable = false, columnDefinition = "jsonb")
  private String evidenceRefsJson;

  @Column(name = "idempotency_key") private String idempotencyKey;
  @Column(name = "created_by_user_id") private UUID createdByUserId;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Column(name = "decided_by_user_id") private UUID decidedByUserId;
  @Column(name = "decided_at") private Instant decidedAt;
  @Column(name = "decision_reason") private String decisionReason;

  protected AiWorkSuggestion() {}

  public AiWorkSuggestion(
      UUID tenantId,
      AiWorkType workType,
      AiWorkSourceType sourceType,
      UUID sourceId,
      String strategyVersion,
      String riskLevel,
      BigDecimal confidence,
      String generatedText,
      String structuredPayloadJson,
      String evidenceRefsJson,
      String idempotencyKey,
      UUID createdByUserId,
      Instant now) {
    this.tenantId = tenantId;
    this.workType = workType.name();
    this.sourceType = sourceType.name();
    this.sourceId = sourceId;
    this.status = AiWorkStatus.GENERATED.name();
    this.strategyVersion = strategyVersion;
    this.riskLevel = riskLevel;
    this.confidence = confidence;
    this.generatedText = generatedText;
    this.structuredPayloadJson = structuredPayloadJson == null ? "{}" : structuredPayloadJson;
    this.evidenceRefsJson = evidenceRefsJson == null ? "[]" : evidenceRefsJson;
    this.idempotencyKey = idempotencyKey;
    this.createdByUserId = createdByUserId;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /**
   * Operator accepts this advisory suggestion. Advisory only: this records intent and never performs
   * a quote/order/inventory/pricing/customer mutation or external write.
   */
  public void accept(UUID decidedByUserId, String reason, Instant now) {
    this.status = AiWorkStatus.ACCEPTED.name();
    this.decidedByUserId = decidedByUserId;
    this.decisionReason = reason;
    this.decidedAt = now;
    this.updatedAt = now;
  }

  /** Operator rejects this advisory suggestion, optionally with a reason. */
  public void reject(UUID decidedByUserId, String reason, Instant now) {
    this.status = AiWorkStatus.REJECTED.name();
    this.decidedByUserId = decidedByUserId;
    this.decisionReason = reason;
    this.decidedAt = now;
    this.updatedAt = now;
  }

  public boolean isDecided() {
    return !AiWorkStatus.GENERATED.name().equals(status);
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getWorkType() { return workType; }
  public String getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public String getStatus() { return status; }
  public String getStrategyVersion() { return strategyVersion; }
  public String getRiskLevel() { return riskLevel; }
  public BigDecimal getConfidence() { return confidence; }
  public String getGeneratedText() { return generatedText; }
  public String getStructuredPayloadJson() { return structuredPayloadJson; }
  public String getEvidenceRefsJson() { return evidenceRefsJson; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public UUID getCreatedByUserId() { return createdByUserId; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public UUID getDecidedByUserId() { return decidedByUserId; }
  public Instant getDecidedAt() { return decidedAt; }
  public String getDecisionReason() { return decisionReason; }
}
