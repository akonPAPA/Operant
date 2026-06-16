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
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * Tenant-scoped record of a single deterministic trust evaluation for an inbound document. Links to
 * the existing {@code sourceDocumentId} and an optional {@code validationRunId}; it does not define a
 * new document domain. Holds the computed {@link TrustRiskLevel}, a 0..100 {@code riskScore}, routing
 * flags, and bounded size metadata only — no raw document text/payload is stored.
 *
 * <p>{@code active} + {@code idempotencyKey}/{@code contentSha256} support idempotent creation: a
 * repeat evaluation for the same tenant + source document + content (or idempotency key) collapses
 * onto the existing active run instead of creating a duplicate.</p>
 */
@Entity
@Table(name = "document_trust_run")
public class DocumentTrustRun {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "source_document_id", nullable = false) private UUID sourceDocumentId;

  /** Optional link to the validation run this trust evaluation accompanied. */
  @Column(name = "validation_run_id") private UUID validationRunId;

  /** Optional link to the fingerprint computed for this document. */
  @Column(name = "fingerprint_id") private UUID fingerprintId;

  /** Hex SHA-256 of the canonical content input; part of the natural idempotency key. */
  @Column(name = "content_sha256", nullable = false, length = 64) private String contentSha256;

  /** Optional caller-supplied idempotency token. */
  @Column(name = "idempotency_key", length = 120) private String idempotencyKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "risk_level", nullable = false, length = 16) private TrustRiskLevel riskLevel;

  /** Numeric risk score clamped to 0..100. */
  @Column(name = "risk_score", nullable = false) private int riskScore;

  /** Bounded decision state token (CONTINUE_WITH_WARNING / REQUIRES_REVIEW / BLOCK_AUTOMATION). */
  @Column(name = "decision_state", nullable = false, length = 32) private String decisionState;

  @Column(name = "requires_human_review", nullable = false) private boolean requiresHumanReview;

  @Column(name = "blocks_automation", nullable = false) private boolean blocksAutomation;

  @Column(name = "duplicate_detected", nullable = false) private boolean duplicateDetected;

  @Column(name = "signal_count", nullable = false) private int signalCount;

  /** Whether this is the current active run for its idempotency key (vs. a superseded record). */
  @Column(name = "active", nullable = false) private boolean active;

  /** Bounded source file size in bytes (long/BIGINT to avoid overflow). */
  @Column(name = "file_size_bytes") private Long fileSizeBytes;

  /** Bounded source page count. */
  @Column(name = "page_count") private Integer pageCount;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected DocumentTrustRun() {}

  public DocumentTrustRun(
      UUID tenantId,
      UUID sourceDocumentId,
      UUID validationRunId,
      UUID fingerprintId,
      String contentSha256,
      String idempotencyKey,
      DocumentTrustDecision decision,
      boolean duplicateDetected,
      int signalCount,
      Long fileSizeBytes,
      Integer pageCount,
      Instant createdAt) {
    this.tenantId = tenantId;
    this.sourceDocumentId = sourceDocumentId;
    this.validationRunId = validationRunId;
    this.fingerprintId = fingerprintId;
    this.contentSha256 = contentSha256;
    this.idempotencyKey = idempotencyKey;
    this.riskLevel = decision.riskLevel();
    this.riskScore = decision.riskScore();
    this.decisionState = decision.state();
    this.requiresHumanReview = decision.requiresHumanReview();
    this.blocksAutomation = decision.blocksAutomation();
    this.duplicateDetected = duplicateDetected;
    this.signalCount = signalCount;
    this.active = true;
    this.fileSizeBytes = fileSizeBytes;
    this.pageCount = pageCount;
    this.createdAt = createdAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getSourceDocumentId() { return sourceDocumentId; }
  public UUID getValidationRunId() { return validationRunId; }
  public UUID getFingerprintId() { return fingerprintId; }
  public String getContentSha256() { return contentSha256; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public TrustRiskLevel getRiskLevel() { return riskLevel; }
  public int getRiskScore() { return riskScore; }
  public String getDecisionState() { return decisionState; }
  public boolean isRequiresHumanReview() { return requiresHumanReview; }
  public boolean isBlocksAutomation() { return blocksAutomation; }
  public boolean isDuplicateDetected() { return duplicateDetected; }
  public int getSignalCount() { return signalCount; }
  public boolean isActive() { return active; }
  public Long getFileSizeBytes() { return fileSizeBytes; }
  public Integer getPageCount() { return pageCount; }
  public Instant getCreatedAt() { return createdAt; }
}
