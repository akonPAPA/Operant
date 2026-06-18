package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Stage12BDtos {
  private Stage12BDtos() {}

  // OP-CAP-31: public request carries business intent only. Actor (id/type) is backend-owned
  // authority resolved from RequestActorResolver / the trusted call site, never from the body —
  // a direct API caller (Postman/curl/CLI/bot) cannot forge who performed the conversion.
  public record ChannelToQuoteRequest(
      String idempotencyKey,
      UUID requestedCustomerAccountId,
      String requestedQuoteType,
      String operatorNotes,
      boolean dryRun,
      boolean forceReview,
      List<UUID> selectedLineItemIds,
      Map<UUID, UUID> selectedSubstituteIds) {}

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

  // OP-CAP-31: operator-safe source summary. Internal identifiers (sourceId, conversionAttemptId,
  // triggeredBy/createdBy actor id, sourceEvidenceId, raw metadata) are not exposed on the default
  // operator response. Diagnostics must live behind a separate admin endpoint/permission.
  public record QuoteSourceContextDto(
      String sourceType,
      String sourceChannel,
      String sourceExternalRef,
      Instant sourceReceivedAt,
      String conversionStatus,
      int candidateLineCount,
      boolean reviewRequired,
      List<QuoteValidationIssueDto> validationIssues) {}

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
