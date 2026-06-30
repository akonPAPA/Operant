package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Stage5Dtos {
  private Stage5Dtos() {}

  public record ValidationRunRequest(UUID extractionResultId, String mode) {}
  public record ValidationRunResponse(UUID id, UUID extractionResultId, String sourceType, String status, String overallStatus, BigDecimal overallConfidence, Instant startedAt, Instant finishedAt, String errorMessage, Instant createdAt) {}

  // Wave 01B — explicit public validation result shape. Domain entities, raw JSON details and
  // extraction/source storage identifiers remain internal.
  public record ExtractionValidationResponse(
      UUID validationRunId, String runStatus, String overallStatus, String routingRecommendation,
      List<LineItemValidationResponse> lineItems, List<ValidationIssueResponse> validationIssues,
      List<ApprovalRequirementResponse> approvalRequirements) {}

  public record LineItemValidationResponse(
      int lineNumber, String rawSku, String rawDescription, String rawQuantity, String rawUom,
      ProductMatchCandidateResponse matchedProductCandidate, String normalizedUom,
      String stockStatus, String priceStatus, String marginStatus,
      List<SubstituteCandidateResponse> substituteCandidates,
      List<ValidationIssueResponse> validationIssues,
      List<ApprovalRequirementResponse> approvalRequirements,
      String routingRecommendation) {}

  public record ProductMatchCandidateResponse(
      UUID productId, String matchType, BigDecimal confidence, String status) {}

  public record SubstituteCandidateResponse(
      UUID id, UUID substituteProductId, String substituteType, String riskLevel,
      BigDecimal rankScore, String reason, String inventoryStatus, String marginStatus,
      boolean requiresApproval, String status) {}

  public record ValidationIssueResponse(
      UUID id, UUID lineItemId, String issueType, String severity, String message,
      String status, Instant createdAt) {}

  public record ApprovalRequirementResponse(
      UUID id, UUID lineItemId, String requirementType, String severity, String reason,
      String status, Instant createdAt) {}

  public record CustomerMatchResponse(
      UUID id, UUID matchedCustomerAccountId, String rawCustomerHint, String matchType,
      BigDecimal confidence, String status) {}

  public record ProductMatchValidationResponse(
      UUID id, UUID lineItemId, UUID matchedProductId, String rawSku, String rawDescription,
      String matchType, BigDecimal confidence, String status) {}

  public record UomNormalizationResponse(
      UUID id, UUID lineItemId, String rawUom, String normalizedUom, String status,
      BigDecimal confidence) {}

  public record InventoryCheckResponse(
      UUID id, UUID lineItemId, UUID productId, UUID locationId, BigDecimal requestedQuantity,
      BigDecimal quantityAvailable, String status) {}

  public record PriceCheckResponse(
      UUID id, UUID lineItemId, UUID productId, BigDecimal unitPrice, String currency,
      String status) {}

  public record DiscountCheckResponse(
      UUID id, UUID lineItemId, BigDecimal requestedDiscountPercent,
      BigDecimal maxAllowedDiscountPercent, boolean requiresApproval, String status) {}

  public record MarginCheckResponse(
      UUID id, UUID lineItemId, UUID productId, BigDecimal grossMarginPercent,
      boolean requiresApproval, String status) {}
}
