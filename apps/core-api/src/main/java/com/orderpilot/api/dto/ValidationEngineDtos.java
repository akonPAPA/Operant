package com.orderpilot.api.dto;

import com.orderpilot.domain.risk.ApprovalRequirementType;
import com.orderpilot.domain.risk.ValidationRiskDecision;
import com.orderpilot.domain.validation.ValidationCaseStatus;
import com.orderpilot.domain.validation.ValidationIssueType;
import com.orderpilot.domain.validation.ValidationSeverity;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-08A DTOs for the deterministic validation/risk engine.
 *
 * <p>Input is an advisory extracted request (independent of the Python worker implementation). The
 * server resolves tenant from {@code TenantContext}; tenant is never trusted from the body. Output is
 * a pure, computed advisory result — it never represents a created quote/order or any mutation.
 */
public final class ValidationEngineDtos {
  private ValidationEngineDtos() {}

  /** Advisory extracted request to validate deterministically. Tenant comes from the server. */
  public record ValidateExtractedRequestCommand(
      String sourceType,
      String sourceId,
      String intent,
      Double documentConfidence,
      List<String> promptInjectionSignals,
      String customerHint,
      String contactHint,
      UUID customerAccountId,
      UUID requestedLocationId,
      String requestedDeliveryDate,
      String notes,
      List<ValidationLineInput> lines) {}

  /**
   * A single advisory requested line. OP-CAP-09A appends optional vehicle/equipment fitment context;
   * it is additive and nullable, so existing callers/JSON payloads remain valid.
   */
  public record ValidationLineInput(
      int lineIndex,
      String rawProductText,
      String rawSkuOrOem,
      BigDecimal quantity,
      String uom,
      Double confidence,
      String evidenceSnippet,
      String notes,
      BigDecimal requestedDiscountPercent,
      String vehicleMake,
      String vehicleModel,
      Integer vehicleYear,
      String vehicleConfiguration) {}

  /** Deterministic validation result. {@code validationCaseId} is null: this slice does not persist. */
  public record ExtractedRequestValidationResult(
      UUID validationCaseId,
      String correlationId,
      String sourceType,
      String sourceId,
      ValidationCaseStatus status,
      ValidationRiskDecision riskDecision,
      Double overallConfidence,
      int riskScore,
      CustomerCandidate matchedCustomer,
      List<ValidationLineResult> lineResults,
      List<ValidationIssueView> issues,
      List<ApprovalRequirementView> approvalRequirements,
      List<String> explanation) {}

  /**
   * Per-line deterministic result. OP-CAP-09A appends product-intelligence fields additively
   * ({@code matchConfidence}, {@code compatibilityStatus}, {@code substituteCandidates},
   * {@code productIntelligenceIssues}); existing field order is preserved for current consumers.
   */
  public record ValidationLineResult(
      int lineIndex,
      String rawProductText,
      String rawSkuOrOem,
      BigDecimal quantity,
      String normalizedUom,
      ProductCandidate matchedProduct,
      String matchType,
      String inventoryStatus,
      String priceStatus,
      String marginStatus,
      boolean substituteRequired,
      List<ValidationIssueView> issues,
      String matchConfidence,
      String compatibilityStatus,
      List<ProductIntelligenceDtos.SubstituteCandidate> substituteCandidates,
      List<ValidationIssueView> productIntelligenceIssues) {}

  /** Safe product candidate view (no cost/internal pricing exposed beyond what callers may see). */
  public record ProductCandidate(
      UUID productId,
      String sku,
      String name,
      String matchType,
      BigDecimal confidence) {}

  /** Safe customer candidate view. */
  public record CustomerCandidate(
      UUID customerAccountId,
      String accountCode,
      String displayName,
      String matchType) {}

  /** Safe issue view. Carries only typed, bounded metadata — never raw payloads or secrets. */
  public record ValidationIssueView(
      ValidationIssueType type,
      ValidationSeverity severity,
      Integer lineIndex,
      String message) {}

  /** Advisory approval requirement view. */
  public record ApprovalRequirementView(
      ApprovalRequirementType type,
      Integer lineIndex,
      String reason) {}
}
