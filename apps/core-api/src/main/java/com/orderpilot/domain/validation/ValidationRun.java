package com.orderpilot.domain.validation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "validation_run")
public class ValidationRun {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "extraction_result_id", nullable = false) private UUID extractionResultId;
  @Column(name = "source_type", nullable = false) private String sourceType;
  @Column(nullable = false) private String status;
  @Column(name = "overall_status", nullable = false) private String overallStatus;
  @Column(name = "overall_confidence", nullable = false) private BigDecimal overallConfidence;
  @Column(name = "started_at") private Instant startedAt;
  @Column(name = "finished_at") private Instant finishedAt;
  @Column(name = "error_message") private String errorMessage;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected ValidationRun() {}

  public ValidationRun(UUID tenantId, UUID extractionResultId, String sourceType, Instant now) {
    this.tenantId = tenantId;
    this.extractionResultId = extractionResultId;
    this.sourceType = sourceType;
    this.status = "VALIDATION_READY";
    this.overallStatus = "VALIDATION_READY";
    this.overallConfidence = BigDecimal.ZERO;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void start(Instant now) { this.status = "RUNNING"; this.startedAt = now; this.updatedAt = now; }
  public void complete(String overallStatus, BigDecimal confidence, Instant now) { this.status = overallStatus; this.overallStatus = overallStatus; this.overallConfidence = confidence; this.finishedAt = now; this.updatedAt = now; }
  public void needsReview(String overallStatus, BigDecimal confidence, Instant now) { this.status = "NEEDS_REVIEW"; this.overallStatus = overallStatus; this.overallConfidence = confidence; this.finishedAt = now; this.updatedAt = now; }
  public void fail(String message, Instant now) { this.status = "FAILED"; this.overallStatus = "FAILED"; this.errorMessage = message; this.finishedAt = now; this.updatedAt = now; }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getExtractionResultId() { return extractionResultId; }
  public String getSourceType() { return sourceType; }
  public String getStatus() { return status; }
  public String getOverallStatus() { return overallStatus; }
  public BigDecimal getOverallConfidence() { return overallConfidence; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getFinishedAt() { return finishedAt; }
  public String getErrorMessage() { return errorMessage; }
  public Instant getCreatedAt() { return createdAt; }
}
