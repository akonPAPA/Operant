package com.orderpilot.api.dto;

import com.orderpilot.api.dto.Stage12ADtos.ApprovalRequest;
import com.orderpilot.api.dto.Stage12ADtos.QuoteLine;
import com.orderpilot.api.dto.Stage12ADtos.ResolvedCustomer;
import com.orderpilot.api.dto.Stage12ADtos.SubstituteCandidate;
import com.orderpilot.api.dto.Stage12ADtos.ValidationIssue;
import com.orderpilot.api.dto.Stage12BDtos.QuoteCandidateLineDto;
import com.orderpilot.api.dto.Stage12BDtos.QuoteSourceContextDto;
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
      UUID conversionAttemptId,
      String sourceType,
      UUID sourceId,
      String sourceChannel,
      CustomerSummary customer,
      int lineCount,
      int validationIssueCount,
      String highestSeverity,
      String status,
      Instant createdAt,
      UUID assignedOperatorId,
      String nextRequiredAction) {}

  public record CustomerSummary(UUID customerAccountId, String displayName, String resolutionStatus) {}

  public record QuoteReviewDetail(
      QuoteHeader header,
      String status,
      QuoteSourceContextDto sourceContext,
      ConversionAttemptSummary conversionAttempt,
      List<QuoteCandidateLineDto> sourceLines,
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
      Instant createdAt,
      UUID auditCorrelationId) {
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
          quote.getCreatedAt(),
          quote.getAuditCorrelationId());
    }
  }

  public record ConversionAttemptSummary(
      UUID id,
      String sourceType,
      UUID sourceId,
      String status,
      String failureCode,
      String failureMessage,
      String requestMode,
      UUID triggeredBy,
      String triggeredByType) {
    public static ConversionAttemptSummary from(QuoteConversionAttempt attempt) {
      if (attempt == null) return null;
      return new ConversionAttemptSummary(attempt.getId(), attempt.getSourceType(), attempt.getSourceId(), attempt.getStatus(), attempt.getFailureCode(), attempt.getFailureMessage(), attempt.getRequestMode(), attempt.getTriggeredBy(), attempt.getTriggeredByType());
    }
  }

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
      UUID sourceId,
      String sourceChannel,
      UUID channelMessageId,
      UUID inboundDocumentId,
      UUID draftQuoteId,
      boolean draftQuoteLinked,
      String status,
      boolean reviewRequired,
      String reasonCode,
      List<String> reasonCodes,
      int issueCount,
      String customerResolution,
      int lineCount,
      String requestMode,
      UUID triggeredBy,
      String triggeredByType,
      Instant createdAt) {}

  public record QuoteConversionAttemptReviewDetail(
      UUID id,
      String sourceType,
      UUID sourceId,
      String sourceChannel,
      UUID channelMessageId,
      UUID inboundDocumentId,
      UUID draftQuoteId,
      boolean draftQuoteLinked,
      String status,
      boolean reviewRequired,
      String reasonCode,
      List<String> reasonCodes,
      int issueCount,
      String customerResolution,
      int lineCount,
      String requestMode,
      UUID triggeredBy,
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
      UUID id,
      String action,
      String entityType,
      String entityId,
      UUID actorId,
      Instant occurredAt,
      String metadata) {
    public static AuditTimelineEvent from(AuditEvent event) {
      return new AuditTimelineEvent(event.getId(), event.getAction(), event.getEntityType(), event.getEntityId(), event.getActorId(), event.getOccurredAt(), event.getMetadata());
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

  public record ResolveValidationIssueCommand(UUID tenantId, UUID actorId, String actorRole, String reasonCode, String note) {}
  public record RejectValidationIssueSuggestionCommand(UUID tenantId, UUID actorId, String actorRole, String reasonCode, String note) {}
  public record ApplyValidationIssueFixCommand(UUID tenantId, UUID actorId, String actorRole, String fixType, Map<String, String> values, String reasonCode, String note) {}
  public record EscalateValidationIssueCommand(UUID tenantId, UUID actorId, String actorRole, String reasonCode, String note) {}
  public record CorrectQuoteCustomerCommand(UUID tenantId, UUID actorId, String actorRole, UUID customerAccountId, String reasonCode, String note) {}
  public record CorrectQuoteLineCommand(UUID tenantId, UUID actorId, String actorRole, BigDecimal quantity, String uom, UUID productId, boolean removeLine, boolean manualFollowUp, String reasonCode, String note) {}
  public record QuoteLineSubstituteCommand(UUID tenantId, UUID actorId, String actorRole, UUID substituteProductId, String reasonCode, String note) {}
}
