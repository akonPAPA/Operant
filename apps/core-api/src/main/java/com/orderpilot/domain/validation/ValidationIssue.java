package com.orderpilot.domain.validation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "validation_issue")
public class ValidationIssue {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "validation_run_id", nullable = false) private UUID validationRunId;
  @Column(name = "extraction_result_id") private UUID extractionResultId;
  @Column(name = "extracted_line_item_id") private UUID extractedLineItemId;
  @Column(name = "extracted_field_id") private UUID extractedFieldId;
  @Column(name = "issue_type", nullable = false) private String issueType;
  @Column(nullable = false) private String severity;
  @Column(nullable = false) private String message;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "details_json", nullable = false, columnDefinition = "jsonb") private String detailsJson;
  @Column(nullable = false) private String status;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected ValidationIssue() {}

  public ValidationIssue(UUID tenantId, UUID validationRunId, UUID extractionResultId, UUID lineItemId, UUID fieldId, String issueType, String severity, String message, String detailsJson, Instant now) {
    this.tenantId = tenantId; this.validationRunId = validationRunId; this.extractionResultId = extractionResultId; this.extractedLineItemId = lineItemId; this.extractedFieldId = fieldId; this.issueType = issueType; this.severity = severity; this.message = message; this.detailsJson = detailsJson == null || detailsJson.isBlank() ? "{}" : detailsJson; this.status = "OPEN"; this.createdAt = now; this.updatedAt = now;
  }

  public void setStatus(String status, Instant now) { this.status = status; this.updatedAt = now; }
  public UUID getId() { return id; }
  public UUID getValidationRunId() { return validationRunId; }
  public UUID getExtractedLineItemId() { return extractedLineItemId; }
  public String getIssueType() { return issueType; }
  public String getSeverity() { return severity; }
  public String getMessage() { return message; }
  public String getDetailsJson() { return detailsJson; }
  public String getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
}
