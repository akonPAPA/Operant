package com.orderpilot.domain.workspace;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "quote_validation_issue")
public class QuoteValidationIssue {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "draft_quote_id", nullable = false) private UUID draftQuoteId;
  @Column(name = "draft_quote_line_id") private UUID draftQuoteLineId;
  @Column(name = "issue_code", nullable = false) private String issueCode;
  @Column(nullable = false) private String severity;
  @Column(nullable = false) private boolean blocking;
  @Column(nullable = false) private String message;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "details_json", columnDefinition = "jsonb", nullable = false) private String detailsJson;
  @Column(nullable = false) private String status;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "resolved_at") private Instant resolvedAt;

  protected QuoteValidationIssue() {}

  public QuoteValidationIssue(UUID tenantId, UUID draftQuoteId, UUID draftQuoteLineId, String issueCode, String severity, boolean blocking, String message, String detailsJson, Instant now) {
    this.tenantId = tenantId;
    this.draftQuoteId = draftQuoteId;
    this.draftQuoteLineId = draftQuoteLineId;
    this.issueCode = issueCode;
    this.severity = severity;
    this.blocking = blocking;
    this.message = message;
    this.detailsJson = detailsJson == null || detailsJson.isBlank() ? "{}" : detailsJson;
    this.status = "OPEN";
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getDraftQuoteId() { return draftQuoteId; }
  public UUID getDraftQuoteLineId() { return draftQuoteLineId; }
  public String getIssueCode() { return issueCode; }
  public String getSeverity() { return severity; }
  public boolean isBlocking() { return blocking; }
  public String getMessage() { return message; }
  public String getDetailsJson() { return detailsJson; }
  public String getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getResolvedAt() { return resolvedAt; }
  public void resolve(Instant now) { this.status = "RESOLVED"; this.blocking = false; this.resolvedAt = now; }
  public void handle(Instant now) { this.status = "HANDLED"; this.blocking = false; this.resolvedAt = now; }
}
