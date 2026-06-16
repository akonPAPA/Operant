package com.orderpilot.domain.validation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-07E deterministic validation issue for an advisory AI extraction result.
 *
 * <p>{@code message} and {@code evidenceRef} are bounded and safe — they never contain raw customer
 * message bodies or full raw document text.
 */
@Entity
@Table(name = "ai_extraction_validation_issue")
public class AiExtractionValidationIssue {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "ai_extraction_validation_id", nullable = false) private UUID aiExtractionValidationId;
  @Column(name = "extraction_result_id", nullable = false) private UUID extractionResultId;
  @Column(name = "source_type", nullable = false) private String sourceType;
  @Column(name = "source_id") private UUID sourceId;
  @Column(name = "issue_code", nullable = false) private String issueCode;
  @Column(nullable = false) private String severity;
  @Column(name = "field_name") private String fieldName;
  @Column(name = "line_index") private Integer lineIndex;
  @Column(nullable = false) private String message;
  @Column(name = "evidence_ref") private String evidenceRef;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected AiExtractionValidationIssue() {}

  public AiExtractionValidationIssue(UUID tenantId, UUID aiExtractionValidationId, UUID extractionResultId,
      String sourceType, UUID sourceId, String issueCode, String severity, String fieldName,
      Integer lineIndex, String message, String evidenceRef, Instant now) {
    this.tenantId = tenantId;
    this.aiExtractionValidationId = aiExtractionValidationId;
    this.extractionResultId = extractionResultId;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.issueCode = issueCode;
    this.severity = severity;
    this.fieldName = fieldName;
    this.lineIndex = lineIndex;
    this.message = message;
    this.evidenceRef = evidenceRef;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getAiExtractionValidationId() { return aiExtractionValidationId; }
  public UUID getExtractionResultId() { return extractionResultId; }
  public String getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public String getIssueCode() { return issueCode; }
  public String getSeverity() { return severity; }
  public String getFieldName() { return fieldName; }
  public Integer getLineIndex() { return lineIndex; }
  public String getMessage() { return message; }
  public String getEvidenceRef() { return evidenceRef; }
  public Instant getCreatedAt() { return createdAt; }
}
