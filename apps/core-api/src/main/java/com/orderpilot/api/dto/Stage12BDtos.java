package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Stage12BDtos {
  private Stage12BDtos() {}

  public record ChannelToQuoteRequest(
      String idempotencyKey,
      UUID requestedCustomerAccountId,
      String requestedQuoteType,
      String operatorNotes,
      boolean dryRun,
      boolean forceReview,
      List<UUID> selectedLineItemIds,
      Map<UUID, UUID> selectedSubstituteIds,
      UUID actorId,
      String actorType) {}

  public record ChannelToQuoteResponse(
      String status,
      UUID quoteId,
      UUID conversionAttemptId,
      String sourceType,
      UUID sourceId,
      String customerResolution,
      int lineCount,
      int acceptedLineCount,
      List<QuoteValidationIssueDto> validationIssues,
      boolean reviewRequired,
      List<UUID> auditEventIds) {}

  public record QuoteSourceContextDto(
      String sourceType,
      UUID sourceId,
      String sourceChannel,
      String sourceExternalRef,
      Instant sourceReceivedAt,
      String triggeredBy,
      String createdByType,
      UUID conversionAttemptId,
      String conversionStatus,
      List<QuoteCandidateLineDto> candidateLines,
      List<QuoteValidationIssueDto> validationIssues,
      Map<String, Object> metadata) {}

  public record QuoteCandidateLineDto(
      UUID sourceLineItemId,
      int lineNumber,
      String rawSkuOrAlias,
      String description,
      BigDecimal quantity,
      String uom,
      LocalDate requestedDate,
      UUID sourceEvidenceId,
      String status) {}

  public record QuoteValidationIssueDto(
      String code,
      String severity,
      boolean blocking,
      String message,
      UUID lineId) {}
}
