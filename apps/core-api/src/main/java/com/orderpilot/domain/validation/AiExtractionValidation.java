package com.orderpilot.domain.validation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-07E persisted deterministic validation + risk routing header for one advisory AI extraction
 * result.
 *
 * <p>One row per (tenant, extraction_result_id) — re-validation replaces the existing row's issues
 * and recomputes this header in place (idempotent). This record only describes the advisory result's
 * risk/routing; it never represents a quote, order, or any business mutation.
 */
@Entity
@Table(name = "ai_extraction_validation")
public class AiExtractionValidation {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "extraction_result_id", nullable = false) private UUID extractionResultId;
  @Column(name = "extraction_run_id") private UUID extractionRunId;
  @Column(name = "processing_job_id") private UUID processingJobId;
  @Column(name = "source_type", nullable = false) private String sourceType;
  @Column(name = "source_id") private UUID sourceId;
  @Column(name = "risk_level", nullable = false) private String riskLevel;
  @Column(name = "routing_decision", nullable = false) private String routingDecision;
  @Column(nullable = false) private String status;
  @Column(name = "issue_count", nullable = false) private int issueCount;
  @Column(name = "highest_severity") private String highestSeverity;
  @Column(name = "prompt_injection_signal_count", nullable = false) private int promptInjectionSignalCount;
  @Column(name = "unknown_product_count", nullable = false) private int unknownProductCount;
  @Column(name = "unknown_customer", nullable = false) private boolean unknownCustomer;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected AiExtractionValidation() {}

  public AiExtractionValidation(UUID tenantId, UUID extractionResultId, UUID extractionRunId,
      UUID processingJobId, String sourceType, UUID sourceId, Instant now) {
    this.tenantId = tenantId;
    this.extractionResultId = extractionResultId;
    this.extractionRunId = extractionRunId;
    this.processingJobId = processingJobId;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** Apply (or re-apply on idempotent re-validation) the deterministic routing outcome. */
  public void apply(String riskLevel, String routingDecision, String status, int issueCount,
      String highestSeverity, int promptInjectionSignalCount, int unknownProductCount,
      boolean unknownCustomer, Instant now) {
    this.riskLevel = riskLevel;
    this.routingDecision = routingDecision;
    this.status = status;
    this.issueCount = issueCount;
    this.highestSeverity = highestSeverity;
    this.promptInjectionSignalCount = promptInjectionSignalCount;
    this.unknownProductCount = unknownProductCount;
    this.unknownCustomer = unknownCustomer;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getExtractionResultId() { return extractionResultId; }
  public UUID getExtractionRunId() { return extractionRunId; }
  public UUID getProcessingJobId() { return processingJobId; }
  public String getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public String getRiskLevel() { return riskLevel; }
  public String getRoutingDecision() { return routingDecision; }
  public String getStatus() { return status; }
  public int getIssueCount() { return issueCount; }
  public String getHighestSeverity() { return highestSeverity; }
  public int getPromptInjectionSignalCount() { return promptInjectionSignalCount; }
  public int getUnknownProductCount() { return unknownProductCount; }
  public boolean isUnknownCustomer() { return unknownCustomer; }
  public Instant getCreatedAt() { return createdAt; }
}
