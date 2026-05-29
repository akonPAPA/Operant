package com.orderpilot.api.dto;

import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteLine;
import com.orderpilot.domain.workspace.QuoteApprovalRequest;
import com.orderpilot.domain.workspace.QuoteValidationIssue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Stage12ADtos {
  private Stage12ADtos() {}

  public record CreateDraftQuoteFromRfqCommand(
      UUID tenantId,
      UUID actorId,
      String actorRole,
      String customerExternalRef,
      String customerName,
      List<RequestedItem> requestedItems,
      String requestedLocation,
      BigDecimal requestedDiscountPercent,
      String idempotencyKey) {}

  public record QuoteApprovalDecisionCommand(
      UUID tenantId,
      UUID actorId,
      String actorRole,
      UUID approvalRequestId,
      String reason,
      String comment,
      String idempotencyKey) {}

  public record RequestedItem(
      String rawSkuOrAlias,
      String description,
      BigDecimal quantity,
      String uom) {}

  public record QuoteTransactionResponse(
      UUID draftQuoteId,
      String status,
      ResolvedCustomer resolvedCustomer,
      List<QuoteLine> lines,
      List<ValidationIssue> validationIssues,
      List<SubstituteCandidate> substituteCandidates,
      boolean approvalRequired,
      List<String> approvalReasons,
      UUID auditCorrelationId,
      List<ApprovalRequest> approvalRequests) {
    public static QuoteTransactionResponse from(
        DraftQuote quote,
        ResolvedCustomer customer,
        List<DraftQuoteLine> lines,
        List<QuoteValidationIssue> issues,
        List<SubstituteCandidate> candidates,
        List<QuoteApprovalRequest> approvals) {
      return new QuoteTransactionResponse(
          quote.getId(),
          quote.getStatus(),
          customer,
          lines.stream().map(QuoteLine::from).toList(),
          issues.stream().map(ValidationIssue::from).toList(),
          candidates,
          !approvals.isEmpty(),
          approvals.stream().map(QuoteApprovalRequest::getReasonCode).distinct().toList(),
          quote.getAuditCorrelationId(),
          approvals.stream().map(ApprovalRequest::from).toList());
    }
  }

  public record ResolvedCustomer(UUID id, String externalRef, String accountCode, String displayName, String status) {}

  public record QuoteLine(
      UUID id,
      int lineNumber,
      String rawSkuOrAlias,
      String normalizedSku,
      UUID productId,
      String productName,
      BigDecimal quantity,
      String uom,
      BigDecimal unitPrice,
      BigDecimal discountPercent,
      BigDecimal lineTotal,
      BigDecimal marginPercent,
      BigDecimal availableStock,
      String validationStatus,
      String issueCodes) {
    public static QuoteLine from(DraftQuoteLine line) {
      return new QuoteLine(line.getId(), line.getLineNumber(), line.getRawSku(), line.getNormalizedSku(), line.getProductId(), line.getProductName(), line.getQuantity(), line.getUom(), line.getUnitPrice(), line.getDiscountPercent(), line.getLineTotal(), line.getMarginPercent(), line.getAvailableStock(), line.getValidationStatus(), line.getIssueCodes());
    }
  }

  public record ValidationIssue(UUID id, UUID lineId, String issueCode, String severity, boolean blocking, String message, String status) {
    public static ValidationIssue from(QuoteValidationIssue issue) {
      return new ValidationIssue(issue.getId(), issue.getDraftQuoteLineId(), issue.getIssueCode(), issue.getSeverity(), issue.isBlocking(), issue.getMessage(), issue.getStatus());
    }
  }

  public record SubstituteCandidate(
      UUID lineId,
      UUID productId,
      String sku,
      String productName,
      String riskLevel,
      String reasonCode,
      BigDecimal availableStock,
      String stockStatus,
      boolean requiresApproval,
      boolean blocked,
      boolean customerAccepted,
      String explanation) {}

  public record ApprovalRequest(UUID id, UUID lineId, String requestType, String severity, String reasonCode, String reason, String status, Instant createdAt) {
    public static ApprovalRequest from(QuoteApprovalRequest request) {
      return new ApprovalRequest(request.getId(), request.getDraftQuoteLineId(), request.getRequestType(), request.getSeverity(), request.getReasonCode(), request.getReason(), request.getStatus(), request.getCreatedAt());
    }
  }

  public record QuoteApprovalStateResponse(
      UUID quoteId,
      String status,
      boolean approvalRequired,
      List<ValidationIssue> blockingIssues,
      List<String> approvalReasons,
      List<ApprovalRequest> approvalRequests,
      ApprovalDecision approvalDecision,
      UUID internalDraftOrderId,
      UUID changeRequestId,
      String externalExecutionStatus,
      UUID auditCorrelationId) {}

  public record QuoteApprovalCommandResponse(
      UUID quoteId,
      String previousStatus,
      String newStatus,
      boolean approvalRequired,
      String approvalDecision,
      List<ValidationIssue> blockingIssues,
      List<String> approvalReasons,
      UUID internalDraftOrderId,
      UUID changeRequestId,
      String externalExecutionStatus,
      UUID auditCorrelationId) {}

  public record ApprovalDecision(
      UUID id,
      UUID approvalRequestId,
      String decision,
      String comment,
      UUID decidedBy,
      Instant decidedAt,
      String previousQuoteStatus,
      String newQuoteStatus,
      UUID auditCorrelationId) {
    public static ApprovalDecision from(com.orderpilot.domain.workspace.QuoteApprovalDecision decision) {
      if (decision == null) {
        return null;
      }
      return new ApprovalDecision(decision.getId(), decision.getApprovalRequestId(), decision.getDecision(), decision.getDecisionComment(), decision.getDecidedBy(), decision.getDecidedAt(), decision.getPreviousQuoteStatus(), decision.getNewQuoteStatus(), decision.getAuditCorrelationId());
    }
  }
}
