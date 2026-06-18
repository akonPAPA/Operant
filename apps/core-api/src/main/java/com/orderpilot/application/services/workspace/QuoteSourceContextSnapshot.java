package com.orderpilot.application.services.workspace;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record QuoteSourceContextSnapshot(
    String sourceType,
    String sourceChannel,
    String sourceExternalRef,
    Instant sourceReceivedAt,
    String conversionStatus,
    int candidateLineCount,
    boolean reviewRequired,
    List<ValidationIssueSnapshot> validationIssues,
    String createdByType,
    List<CandidateLineSnapshot> candidateLines) {

  public record ValidationIssueSnapshot(
      String code,
      String severity,
      boolean blocking,
      String message,
      UUID lineId) {}

  public record CandidateLineSnapshot(
      int lineNumber,
      String rawSkuOrAlias,
      String description,
      BigDecimal quantity,
      String uom,
      LocalDate requestedDate,
      String status) {}
}
