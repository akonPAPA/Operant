package com.orderpilot.domain.trust.ai;

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
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * A normalized, tenant-scoped evidence pointer for an {@link AiMemoryRecord}. It references an existing
 * document/trust/validation/payment/correction record by type + bounded reference (+ optional source id
 * and field key). It NEVER duplicates raw document text, OCR, extracted values, or prompts.
 */
@Entity
@Table(name = "ai_memory_evidence_ref")
public class AiMemoryEvidenceRef {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "ai_memory_record_id", nullable = false) private UUID aiMemoryRecordId;

  @Enumerated(EnumType.STRING)
  @Column(name = "evidence_type", nullable = false, length = 24) private AiMemoryEvidenceType evidenceType;

  /** Bounded metadata reference pointer — never raw content. */
  @Column(name = "evidence_ref", nullable = false, length = 160) private String evidenceRef;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", length = 32) private AiMemorySourceType sourceType;

  @Column(name = "source_id") private UUID sourceId;

  @Column(name = "field_key", length = 64) private String fieldKey;

  @Column(name = "confidence", precision = 5, scale = 4) private BigDecimal confidence;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected AiMemoryEvidenceRef() {}

  public AiMemoryEvidenceRef(UUID tenantId, UUID aiMemoryRecordId, AiMemoryEvidenceType evidenceType,
      String evidenceRef, AiMemorySourceType sourceType, UUID sourceId, String fieldKey,
      BigDecimal confidence, Instant now) {
    this.tenantId = tenantId;
    this.aiMemoryRecordId = aiMemoryRecordId;
    this.evidenceType = evidenceType;
    this.evidenceRef = evidenceRef;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.fieldKey = fieldKey;
    this.confidence = confidence;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getAiMemoryRecordId() { return aiMemoryRecordId; }
  public AiMemoryEvidenceType getEvidenceType() { return evidenceType; }
  public String getEvidenceRef() { return evidenceRef; }
  public AiMemorySourceType getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public String getFieldKey() { return fieldKey; }
  public BigDecimal getConfidence() { return confidence; }
  public Instant getCreatedAt() { return createdAt; }
}
