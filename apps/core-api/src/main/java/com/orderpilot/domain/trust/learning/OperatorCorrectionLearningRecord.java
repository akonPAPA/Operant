package com.orderpilot.domain.trust.learning;

import com.orderpilot.domain.trust.ai.AiMemorySourceType;
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
 * OP-CAP-18 Operator Correction Learning Loop.
 *
 * Tenant-scoped, sanitized record of an operator correction that may become governed advisory AI memory.
 * It stores only safe metadata: bounded {@code normalizedCorrection}/{@code correctionSummary}, SHA-256
 * hashes of any raw previous/corrected values (never the raw values themselves), and deterministic
 * learning eligibility. An operator correction is not automatically high-authority — projection to
 * {@code HUMAN_APPROVED} memory only happens after explicit approval, and price/stock corrections never
 * become authoritative.
 */
@Entity
@Table(name = "operator_correction_learning_record")
public class OperatorCorrectionLearningRecord {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "correction_type", nullable = false, length = 48) private OperatorCorrectionType correctionType;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 32) private AiMemorySourceType sourceType;

  @Column(name = "source_id") private UUID sourceId;

  @Column(name = "target_type", nullable = false, length = 48) private String targetType;

  @Column(name = "target_id") private UUID targetId;

  @Column(name = "field_key", length = 64) private String fieldKey;

  /** SHA-256 hex of the raw previous value (never the raw value). */
  @Column(name = "previous_value_hash", length = 64) private String previousValueHash;

  /** SHA-256 hex of the raw corrected value (never the raw value). */
  @Column(name = "corrected_value_hash", length = 64) private String correctedValueHash;

  /** Bounded, domain-normalized safe correction value (only when safe to store). */
  @Column(name = "normalized_correction", length = 256) private String normalizedCorrection;

  @Column(name = "correction_summary", nullable = false, length = 512) private String correctionSummary;

  @Column(name = "confidence", nullable = false, precision = 5, scale = 4) private BigDecimal confidence;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 24) private OperatorCorrectionStatus status;

  @Column(name = "learning_eligible", nullable = false) private boolean learningEligible;

  @Column(name = "linked_ai_memory_record_id") private UUID linkedAiMemoryRecordId;

  @Column(name = "created_by") private UUID createdBy;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  @Column(name = "reviewed_at") private Instant reviewedAt;

  @Column(name = "rejected_at") private Instant rejectedAt;

  @Column(name = "rejection_reason", length = 280) private String rejectionReason;

  protected OperatorCorrectionLearningRecord() {}

  public OperatorCorrectionLearningRecord(UUID tenantId, OperatorCorrectionType correctionType,
      AiMemorySourceType sourceType, UUID sourceId, String targetType, UUID targetId, String fieldKey,
      String previousValueHash, String correctedValueHash, String normalizedCorrection,
      String correctionSummary, BigDecimal confidence, boolean learningEligible, UUID createdBy,
      Instant now) {
    this.tenantId = tenantId;
    this.correctionType = correctionType;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.targetType = targetType;
    this.targetId = targetId;
    this.fieldKey = fieldKey;
    this.previousValueHash = previousValueHash;
    this.correctedValueHash = correctedValueHash;
    this.normalizedCorrection = normalizedCorrection;
    this.correctionSummary = correctionSummary;
    this.confidence = confidence;
    this.status = OperatorCorrectionStatus.RECORDED;
    this.learningEligible = learningEligible;
    this.createdBy = createdBy;
    this.createdAt = now;
  }

  /** Operator approval gate — makes the correction eligible and ready for projection. */
  public void approveForLearning(Instant now) {
    this.status = OperatorCorrectionStatus.APPROVED_FOR_LEARNING;
    this.learningEligible = true;
    this.reviewedAt = now;
  }

  public void reject(String reason, Instant now) {
    this.status = OperatorCorrectionStatus.REJECTED;
    this.learningEligible = false;
    this.rejectedAt = now;
    this.rejectionReason = reason;
    this.reviewedAt = now;
  }

  public void markProjected(UUID aiMemoryRecordId, Instant now) {
    this.status = OperatorCorrectionStatus.PROJECTED_TO_MEMORY;
    this.linkedAiMemoryRecordId = aiMemoryRecordId;
    this.reviewedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public OperatorCorrectionType getCorrectionType() { return correctionType; }
  public AiMemorySourceType getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public String getTargetType() { return targetType; }
  public UUID getTargetId() { return targetId; }
  public String getFieldKey() { return fieldKey; }
  public String getPreviousValueHash() { return previousValueHash; }
  public String getCorrectedValueHash() { return correctedValueHash; }
  public String getNormalizedCorrection() { return normalizedCorrection; }
  public String getCorrectionSummary() { return correctionSummary; }
  public BigDecimal getConfidence() { return confidence; }
  public OperatorCorrectionStatus getStatus() { return status; }
  public boolean isLearningEligible() { return learningEligible; }
  public UUID getLinkedAiMemoryRecordId() { return linkedAiMemoryRecordId; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getReviewedAt() { return reviewedAt; }
  public Instant getRejectedAt() { return rejectedAt; }
  public String getRejectionReason() { return rejectionReason; }
}
