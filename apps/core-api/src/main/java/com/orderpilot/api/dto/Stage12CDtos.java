package com.orderpilot.api.dto;

import com.orderpilot.api.dto.Stage12ADtos.ApprovalRequest;
import com.orderpilot.api.dto.Stage12ADtos.QuoteLine;
import com.orderpilot.api.dto.Stage12ADtos.ResolvedCustomer;
import com.orderpilot.api.dto.Stage12ADtos.SubstituteCandidate;
import com.orderpilot.api.dto.Stage12ADtos.ValidationIssue;
import com.orderpilot.api.dto.Stage12BDtos.QuoteValidationIssueDto;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.QuoteConversionAttempt;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Stage12CDtos {
  private Stage12CDtos() {}

  public record QuoteReviewQueueRow(
      UUID quoteId,
      String sourceType,
      String sourceChannel,
      CustomerSummary customer,
      int lineCount,
      int validationIssueCount,
      String highestSeverity,
      String status,
      Instant createdAt,
      String nextRequiredAction) {}

  public record CustomerSummary(UUID customerAccountId, String displayName, String resolutionStatus) {}

  public record QuoteReviewDetail(
      QuoteHeader header,
      String status,
      QuoteReviewSourceContext sourceContext,
      ConversionAttemptSummary conversionAttempt,
      List<QuoteReviewCandidateLine> sourceLines,
      List<QuoteLine> draftQuoteLines,
      List<ValidationIssue> validationIssues,
      List<SubstituteCandidate> proposedSubstitutes,
      PricingRiskSummary pricingSummary,
      List<ApprovalRequest> approvalRequirements,
      List<AuditTimelineEvent> auditTimeline,
      List<String> reviewRequiredReasons) {}

  public record QuoteHeader(
      UUID quoteId,
      String quoteNumber,
      CustomerSummary customer,
      String currency,
      BigDecimal subtotalAmount,
      BigDecimal discountAmount,
      BigDecimal totalAmount,
      BigDecimal marginPercent,
      boolean requiresHumanReview,
      Instant createdAt) {
    public static QuoteHeader from(DraftQuote quote) {
      return new QuoteHeader(
          quote.getId(),
          quote.getQuoteNumber(),
          new CustomerSummary(quote.getCustomerAccountId(), quote.getCustomerDisplayName(), quote.getCustomerAccountId() == null ? "UNRESOLVED" : "RESOLVED"),
          quote.getCurrency(),
          quote.getSubtotalAmount(),
          quote.getDiscountAmount(),
          quote.getTotalAmount(),
          quote.getMarginPercent(),
          quote.isRequiresHumanReview(),
          quote.getCreatedAt());
    }
  }

  public record ConversionAttemptSummary(
      UUID id,
      String sourceType,
      String status,
      String failureCode,
      String failureMessage,
      String requestMode,
      String triggeredByType) {
    public static ConversionAttemptSummary from(QuoteConversionAttempt attempt) {
      if (attempt == null) return null;
      return new ConversionAttemptSummary(attempt.getId(), attempt.getSourceType(), attempt.getStatus(), attempt.getFailureCode(), attempt.getFailureMessage(), attempt.getRequestMode(), attempt.getTriggeredByType());
    }
  }

  public record QuoteReviewSourceContext(
      String sourceType,
      String sourceChannel,
      Instant sourceReceivedAt,
      String createdByType,
      String conversionStatus) {}

  public record QuoteReviewCandidateLine(
      int lineNumber,
      String rawSkuOrAlias,
      String description,
      BigDecimal quantity,
      String uom,
      java.time.LocalDate requestedDate,
      String status) {}

  public record QuoteConversionAttemptReviewFilter(
      String status,
      Boolean reviewRequired,
      String reasonCode,
      String sourceChannel,
      Boolean draftQuoteLinked,
      Instant createdFrom,
      Instant createdTo) {}

  public record QuoteConversionAttemptReviewItem(
      UUID id,
      String sourceType,
      String sourceChannel,
      boolean draftQuoteLinked,
      String status,
      boolean reviewRequired,
      String reasonCode,
      List<String> reasonCodes,
      int issueCount,
      String customerResolution,
      int lineCount,
      String requestMode,
      String triggeredByType,
      Instant createdAt) {}

  public record QuoteConversionAttemptReviewDetail(
      UUID id,
      String sourceType,
      String sourceChannel,
      boolean draftQuoteLinked,
      String status,
      boolean reviewRequired,
      String reasonCode,
      List<String> reasonCodes,
      int issueCount,
      String customerResolution,
      int lineCount,
      String requestMode,
      String triggeredByType,
      Instant createdAt,
      Map<String, Object> safeMetadata,
      List<QuoteValidationIssueDto> validationIssues) {}

  public record PricingRiskSummary(
      BigDecimal subtotalAmount,
      BigDecimal discountAmount,
      BigDecimal totalAmount,
      BigDecimal marginPercent,
      boolean marginRisk,
      boolean discountRisk,
      boolean approvalRequired) {}

  public record AuditTimelineEvent(
      String action,
      Instant occurredAt,
      String metadata) {
    public static AuditTimelineEvent from(AuditEvent event) {
      return new AuditTimelineEvent(event.getAction(), event.getOccurredAt(), event.getMetadata());
    }
  }

  public record QuoteReviewCommandResult(
      UUID quoteId,
      String previousStatus,
      String newStatus,
      String action,
      List<ValidationIssue> validationIssues,
      List<String> reviewRequiredReasons,
      boolean approvalRequired,
      String validationSummary) {}

  // OP-CAP-36: operator-safe assembled draft quote summary. Exposes only
  // backend-owned, display-safe fields. No tenantId/actorId/createdBy/approvedBy,
  // no sourceId, no auditEventIds, no raw internal IDs other than the quoteId
  // public workflow handle already used across the Quote Review contract.
  public record QuoteDraftSummary(
      UUID quoteId,
      String quoteNumber,
      String draftStatus,
      CustomerSummary customer,
      String currency,
      BigDecimal subtotalAmount,
      BigDecimal discountAmount,
      BigDecimal totalAmount,
      BigDecimal marginPercent,
      int lineCount,
      int unresolvedBlockingIssueCount,
      int warningCount,
      int stockWarningCount,
      boolean approvalRequired,
      String riskLevel,
      String marginStatus,
      String validationSummary,
      String nextAction,
      String operatorMessage,
      String externalExecution,
      Instant assembledAt,
      // OP-CAP-37: safe business status of the internal external-sync ChangeRequest
      // candidate. "PREPARED" once a tenant-scoped, non-executed candidate exists;
      // "PENDING_INTERNAL_APPROVAL" while approval is still required (no candidate
      // this slice). Never exposes the candidate id, target system, or connector data.
      String externalSyncCandidateStatus) {}

  public record ResolveValidationIssueRequest(String reasonCode, String note) {}
  public record RejectValidationIssueSuggestionRequest(String reasonCode, String note) {}
  public record ApplyValidationIssueFixRequest(String fixType, Map<String, String> values, String reasonCode, String note) {}
  public record EscalateValidationIssueRequest(String reasonCode, String note) {}
  public record CorrectQuoteCustomerRequest(UUID customerAccountId, String reasonCode, String note) {}
  public record CorrectQuoteLineRequest(BigDecimal quantity, String uom, UUID productId, boolean removeLine, boolean manualFollowUp, String reasonCode, String note) {}
  public record QuoteLineSubstituteRequest(UUID substituteProductId, String reasonCode, String note) {}
  // OP-CAP-36: business intent only. Any client-supplied authority/state/total
  // field is dropped on deserialization and never reaches the command.
  public record AssembleQuoteDraftRequest(String reasonCode, String note) {}

  public record ResolveValidationIssueCommand(UUID tenantId, UUID actorId, String actorRole, String reasonCode, String note) {}
  public record RejectValidationIssueSuggestionCommand(UUID tenantId, UUID actorId, String actorRole, String reasonCode, String note) {}
  public record ApplyValidationIssueFixCommand(UUID tenantId, UUID actorId, String actorRole, String fixType, Map<String, String> values, String reasonCode, String note) {}
  public record EscalateValidationIssueCommand(UUID tenantId, UUID actorId, String actorRole, String reasonCode, String note) {}
  public record CorrectQuoteCustomerCommand(UUID tenantId, UUID actorId, String actorRole, UUID customerAccountId, String reasonCode, String note) {}
  public record CorrectQuoteLineCommand(UUID tenantId, UUID actorId, String actorRole, BigDecimal quantity, String uom, UUID productId, boolean removeLine, boolean manualFollowUp, String reasonCode, String note) {}
  public record QuoteLineSubstituteCommand(UUID tenantId, UUID actorId, String actorRole, UUID substituteProductId, String reasonCode, String note) {}
  public record AssembleQuoteDraftCommand(UUID tenantId, UUID actorId, String actorRole, String reasonCode, String note) {}
}
