package com.orderpilot.domain.workspace;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "quote_conversion_attempt")
public class QuoteConversionAttempt {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "source_type", nullable = false) private String sourceType;
  @Column(name = "source_id", nullable = false) private UUID sourceId;
  @Column(nullable = false) private String status;
  @Column(name = "quote_id") private UUID quoteId;
  @Column(name = "failure_code") private String failureCode;
  @Column(name = "failure_message") private String failureMessage;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "validation_summary_json", nullable = false, columnDefinition = "jsonb") private String validationSummaryJson;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "triggered_by") private UUID triggeredBy;
  @Column(name = "triggered_by_type", nullable = false) private String triggeredByType;
  @Column(name = "idempotency_key") private String idempotencyKey;
  @Column(name = "request_mode", nullable = false) private String requestMode;

  protected QuoteConversionAttempt() {}

  public QuoteConversionAttempt(UUID tenantId, String sourceType, UUID sourceId, String status, String validationSummaryJson, UUID triggeredBy, String triggeredByType, String idempotencyKey, String requestMode, Instant now) {
    this.tenantId = tenantId;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.status = status;
    this.validationSummaryJson = validationSummaryJson == null || validationSummaryJson.isBlank() ? "{}" : validationSummaryJson;
    this.triggeredBy = triggeredBy;
    this.triggeredByType = triggeredByType == null || triggeredByType.isBlank() ? "SYSTEM" : triggeredByType;
    this.idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey;
    this.requestMode = requestMode == null || requestMode.isBlank() ? "CREATE" : requestMode;
    this.createdAt = now;
  }

  public void markDraftCreated(UUID quoteId, String status, String validationSummaryJson) {
    this.status = status == null || status.isBlank() ? "READY_FOR_DRAFT_QUOTE" : status;
    this.quoteId = quoteId;
    this.validationSummaryJson = validationSummaryJson == null || validationSummaryJson.isBlank() ? "{}" : validationSummaryJson;
  }

  public void markTerminal(String status, String failureCode, String failureMessage, String validationSummaryJson) {
    this.status = status;
    this.failureCode = failureCode;
    this.failureMessage = failureMessage;
    this.validationSummaryJson = validationSummaryJson == null || validationSummaryJson.isBlank() ? "{}" : validationSummaryJson;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public String getStatus() { return status; }
  public UUID getQuoteId() { return quoteId; }
  public String getFailureCode() { return failureCode; }
  public String getFailureMessage() { return failureMessage; }
  public String getValidationSummaryJson() { return validationSummaryJson; }
  public Instant getCreatedAt() { return createdAt; }
  public UUID getTriggeredBy() { return triggeredBy; }
  public String getTriggeredByType() { return triggeredByType; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public String getRequestMode() { return requestMode; }
}
