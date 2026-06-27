package com.orderpilot.api.dto;

import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteLine;
import com.orderpilot.domain.workspace.QuoteValidationIssue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Stage11ADtos {
  private Stage11ADtos() {}

  public record RfqLineInput(String rawText, String rawSku, BigDecimal quantity, String uom, String requestedLocation) {}

  /** Public HTTP intent. Actor and role are resolved by the controller from trusted runtime context. */
  public record LegacyDraftQuoteCreateRequest(
      String sourceType,
      UUID sourceMessageId,
      UUID sourceDocumentId,
      String customerHint,
      String rawMessageText,
      List<RfqLineInput> lineItems) {}

  /** Internal service command. Never bind this type directly to a public request body. */
  public record CreateDraftQuoteFromRfqRequest(
      UUID actorId,
      String actorRole,
      String sourceType,
      UUID sourceMessageId,
      UUID sourceDocumentId,
      String customerHint,
      String rawMessageText,
      List<RfqLineInput> lineItems) {}

  public record DraftQuoteResponse(
      UUID id,
      UUID tenantId,
      String quoteNumber,
      String sourceType,
      UUID sourceMessageId,
      UUID sourceDocumentId,
      UUID customerAccountId,
      String customerDisplayName,
      String status,
      String validationStatus,
      boolean requiresHumanReview,
      String currency,
      BigDecimal subtotalAmount,
      BigDecimal discountAmount,
      BigDecimal totalAmount,
      Instant createdAt,
      List<DraftQuoteLineResponse> lines,
      List<QuoteValidationIssueResponse> issues) {
    public static DraftQuoteResponse from(DraftQuote quote, List<DraftQuoteLine> lines, List<QuoteValidationIssue> issues) {
      return from(quote, lines, issues, java.util.Map.of());
    }

    public static DraftQuoteResponse from(DraftQuote quote, List<DraftQuoteLine> lines, List<QuoteValidationIssue> issues, java.util.Map<UUID, List<SubstituteCandidateResponse>> substitutionCandidates) {
      return new DraftQuoteResponse(
          quote.getId(),
          quote.getTenantId(),
          quote.getQuoteNumber(),
          quote.getSourceType(),
          quote.getSourceMessageId(),
          quote.getSourceDocumentId(),
          quote.getCustomerAccountId(),
          quote.getCustomerDisplayName(),
          quote.getStatus(),
          quote.getValidationStatus(),
          quote.isRequiresHumanReview(),
          quote.getCurrency(),
          quote.getSubtotalAmount(),
          quote.getDiscountAmount(),
          quote.getTotalAmount(),
          quote.getCreatedAt(),
          lines.stream().map(line -> DraftQuoteLineResponse.from(line, substitutionCandidates.getOrDefault(line.getId(), List.of()))).toList(),
          issues.stream().map(QuoteValidationIssueResponse::from).toList());
    }
  }

  public record DraftQuoteLineResponse(
      UUID id,
      int lineNumber,
      String rawText,
      String rawSku,
      String normalizedSku,
      UUID productId,
      String productName,
      BigDecimal quantity,
      String uom,
      String requestedLocation,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      BigDecimal availableStock,
      BigDecimal confidenceScore,
      String validationStatus,
      String issueCodes,
      String substituteDecisionStatus,
      String substituteDecisionReasonCode,
      UUID substituteDecidedBy,
      Instant substituteDecidedAt,
      String substituteDecisionNote,
      List<SubstituteCandidateResponse> substitutionCandidates) {
    public static DraftQuoteLineResponse from(DraftQuoteLine line) {
      return from(line, List.of());
    }

    public static DraftQuoteLineResponse from(DraftQuoteLine line, List<SubstituteCandidateResponse> substitutionCandidates) {
      return new DraftQuoteLineResponse(line.getId(), line.getLineNumber(), line.getRawText(), line.getRawSku(), line.getNormalizedSku(), line.getProductId(), line.getProductName(), line.getQuantity(), line.getUom(), line.getRequestedLocation(), line.getUnitPrice(), line.getLineTotal(), line.getAvailableStock(), line.getConfidenceScore(), line.getValidationStatus(), line.getIssueCodes(), line.getSubstituteDecisionStatus(), line.getSubstituteDecisionReasonCode(), line.getSubstituteDecidedBy(), line.getSubstituteDecidedAt(), line.getSubstituteDecisionNote(), substitutionCandidates);
    }
  }

  public record SubstituteCandidateResponse(
      UUID productId,
      String sku,
      String productName,
      String relationType,
      String riskLevel,
      String compatibilityMatchReason,
      String reasonCode,
      String matchedSource,
      BigDecimal availableStock,
      String stockStatus,
      boolean requiresApproval,
      boolean blocked,
      boolean customerAccepted,
      String explanation) {}

  public record QuoteValidationIssueResponse(UUID id, UUID draftQuoteLineId, String issueCode, String severity, boolean blocking, String message, String status) {
    public static QuoteValidationIssueResponse from(QuoteValidationIssue issue) {
      return new QuoteValidationIssueResponse(issue.getId(), issue.getDraftQuoteLineId(), issue.getIssueCode(), issue.getSeverity(), issue.isBlocking(), issue.getMessage(), issue.getStatus());
    }
  }

  public record SubstituteDecisionCommand(UUID actorId, String actorRole, UUID substituteProductId, String note) {}
  public record QuoteLifecycleCommand(UUID actorId, String actorRole, String reason) {}

  /** Public substitute intent; actor and role are backend-owned. */
  public record LegacySubstituteDecisionRequest(UUID substituteProductId, String note) {}

  /** Public lifecycle intent; actor and role are backend-owned. */
  public record LegacyQuoteLifecycleRequest(String reason) {}
}
