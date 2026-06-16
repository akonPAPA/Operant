package com.orderpilot.domain.validation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-07F AI validation handoff — the safe, operator-reviewable projection of a deterministic AI
 * advisory validation routing decision (OP-CAP-07E {@code AiExtractionValidation}).
 *
 * <p>One row per (tenant, validation_id). Re-generation refreshes this row in place. It carries only
 * bounded, audit-safe summary fields — never raw document/message text, never the full result JSON.
 * It is NOT a quote/order, holds no business authority, and creating/refreshing it mutates no master
 * data and triggers no external write. {@code draftEligible} is the only gate toward a future
 * draft-preparation workflow and is true only for {@code READY_FOR_DRAFT_REVIEW}.
 */
@Entity
@Table(name = "ai_validation_handoff")
public class AiValidationHandoff {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "validation_id", nullable = false) private UUID validationId;
  @Column(name = "extraction_result_id", nullable = false) private UUID extractionResultId;
  @Column(name = "extraction_run_id") private UUID extractionRunId;
  @Column(name = "processing_job_id") private UUID processingJobId;
  @Column(name = "source_type", nullable = false) private String sourceType;
  @Column(name = "source_id") private UUID sourceId;
  @Column(nullable = false) private String status;
  @Column(name = "routing_decision", nullable = false) private String routingDecision;
  @Column(name = "risk_level", nullable = false) private String riskLevel;
  @Column private String intent;
  @Column(name = "customer_ref") private String customerRef;
  @Column(name = "line_count", nullable = false) private int lineCount;
  @Column(name = "issue_count", nullable = false) private int issueCount;
  @Column(name = "highest_severity") private String highestSeverity;
  @Column(name = "prompt_injection_signal_count", nullable = false) private int promptInjectionSignalCount;
  @Column(name = "unknown_customer", nullable = false) private boolean unknownCustomer;
  @Column(name = "draft_eligible", nullable = false) private boolean draftEligible;
  @Column(name = "issue_summary", nullable = false) private String issueSummary;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected AiValidationHandoff() {}

  public AiValidationHandoff(UUID tenantId, UUID validationId, UUID extractionResultId,
      UUID extractionRunId, UUID processingJobId, String sourceType, UUID sourceId, Instant now) {
    this.tenantId = tenantId;
    this.validationId = validationId;
    this.extractionResultId = extractionResultId;
    this.extractionRunId = extractionRunId;
    this.processingJobId = processingJobId;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** Apply (or refresh on idempotent re-generation) the derived handoff summary. */
  public void apply(AiValidationHandoffStatus status, String routingDecision, String riskLevel,
      String intent, String customerRef, int lineCount, int issueCount, String highestSeverity,
      int promptInjectionSignalCount, boolean unknownCustomer, String issueSummary, Instant now) {
    this.status = status.name();
    this.routingDecision = routingDecision;
    this.riskLevel = riskLevel;
    this.intent = intent;
    this.customerRef = customerRef;
    this.lineCount = lineCount;
    this.issueCount = issueCount;
    this.highestSeverity = highestSeverity;
    this.promptInjectionSignalCount = promptInjectionSignalCount;
    this.unknownCustomer = unknownCustomer;
    this.draftEligible = status.isDraftEligible();
    this.issueSummary = issueSummary == null ? "" : issueSummary;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getValidationId() { return validationId; }
  public UUID getExtractionResultId() { return extractionResultId; }
  public UUID getExtractionRunId() { return extractionRunId; }
  public UUID getProcessingJobId() { return processingJobId; }
  public String getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public String getStatus() { return status; }
  public String getRoutingDecision() { return routingDecision; }
  public String getRiskLevel() { return riskLevel; }
  public String getIntent() { return intent; }
  public String getCustomerRef() { return customerRef; }
  public int getLineCount() { return lineCount; }
  public int getIssueCount() { return issueCount; }
  public String getHighestSeverity() { return highestSeverity; }
  public int getPromptInjectionSignalCount() { return promptInjectionSignalCount; }
  public boolean isUnknownCustomer() { return unknownCustomer; }
  public boolean isDraftEligible() { return draftEligible; }
  public String getIssueSummary() { return issueSummary; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
