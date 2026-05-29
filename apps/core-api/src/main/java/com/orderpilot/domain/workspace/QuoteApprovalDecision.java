package com.orderpilot.domain.workspace;

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
@Table(name = "quote_approval_decision")
public class QuoteApprovalDecision {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "draft_quote_id", nullable = false) private UUID draftQuoteId;
  @Column(name = "approval_request_id") private UUID approvalRequestId;
  @Column(nullable = false) private String decision;
  @Column(name = "decision_comment") private String decisionComment;
  @Column(name = "decided_by") private UUID decidedBy;
  @Column(name = "decided_at", nullable = false) private Instant decidedAt;
  @Column(name = "previous_quote_status", nullable = false) private String previousQuoteStatus;
  @Column(name = "new_quote_status", nullable = false) private String newQuoteStatus;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "resolved_reasons_json", columnDefinition = "jsonb", nullable = false) private String resolvedReasonsJson;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "blocking_reasons_json", columnDefinition = "jsonb", nullable = false) private String blockingReasonsJson;
  @Column(name = "audit_correlation_id", nullable = false) private UUID auditCorrelationId;

  protected QuoteApprovalDecision() {}

  public QuoteApprovalDecision(UUID tenantId, UUID draftQuoteId, UUID approvalRequestId, String decision, String decisionComment, UUID decidedBy, Instant decidedAt, String previousQuoteStatus, String newQuoteStatus, String resolvedReasonsJson, String blockingReasonsJson, UUID auditCorrelationId) {
    this.tenantId = tenantId;
    this.draftQuoteId = draftQuoteId;
    this.approvalRequestId = approvalRequestId;
    this.decision = decision;
    this.decisionComment = decisionComment;
    this.decidedBy = decidedBy;
    this.decidedAt = decidedAt;
    this.previousQuoteStatus = previousQuoteStatus;
    this.newQuoteStatus = newQuoteStatus;
    this.resolvedReasonsJson = resolvedReasonsJson == null || resolvedReasonsJson.isBlank() ? "[]" : resolvedReasonsJson;
    this.blockingReasonsJson = blockingReasonsJson == null || blockingReasonsJson.isBlank() ? "[]" : blockingReasonsJson;
    this.auditCorrelationId = auditCorrelationId == null ? UUID.randomUUID() : auditCorrelationId;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getDraftQuoteId() { return draftQuoteId; }
  public UUID getApprovalRequestId() { return approvalRequestId; }
  public String getDecision() { return decision; }
  public String getDecisionComment() { return decisionComment; }
  public UUID getDecidedBy() { return decidedBy; }
  public Instant getDecidedAt() { return decidedAt; }
  public String getPreviousQuoteStatus() { return previousQuoteStatus; }
  public String getNewQuoteStatus() { return newQuoteStatus; }
  public UUID getAuditCorrelationId() { return auditCorrelationId; }
}
