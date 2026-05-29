package com.orderpilot.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "quote_approval_request")
public class QuoteApprovalRequest {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "draft_quote_id", nullable = false) private UUID draftQuoteId;
  @Column(name = "draft_quote_line_id") private UUID draftQuoteLineId;
  @Column(name = "request_type", nullable = false) private String requestType;
  @Column(nullable = false) private String severity;
  @Column(name = "reason_code", nullable = false) private String reasonCode;
  @Column(nullable = false) private String reason;
  @Column(nullable = false) private String status;
  @Column(name = "decision_comment") private String decisionComment;
  @Column(name = "decided_by") private UUID decidedBy;
  @Column(name = "decided_at") private Instant decidedAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected QuoteApprovalRequest() {}

  public QuoteApprovalRequest(UUID tenantId, UUID draftQuoteId, UUID draftQuoteLineId, String requestType, String severity, String reasonCode, String reason, Instant now) {
    this.tenantId = tenantId;
    this.draftQuoteId = draftQuoteId;
    this.draftQuoteLineId = draftQuoteLineId;
    this.requestType = requestType;
    this.severity = severity;
    this.reasonCode = reasonCode;
    this.reason = reason;
    this.status = "OPEN";
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getDraftQuoteId() { return draftQuoteId; }
  public UUID getDraftQuoteLineId() { return draftQuoteLineId; }
  public String getRequestType() { return requestType; }
  public String getSeverity() { return severity; }
  public String getReasonCode() { return reasonCode; }
  public String getReason() { return reason; }
  public String getStatus() { return status; }
  public String getDecisionComment() { return decisionComment; }
  public UUID getDecidedBy() { return decidedBy; }
  public Instant getDecidedAt() { return decidedAt; }
  public Instant getCreatedAt() { return createdAt; }

  public void decide(String status, String comment, UUID actorId, Instant now) {
    this.status = status;
    this.decisionComment = comment;
    this.decidedBy = actorId;
    this.decidedAt = now;
    this.updatedAt = now;
  }
}
