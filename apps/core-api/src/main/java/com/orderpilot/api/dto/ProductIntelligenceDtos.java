package com.orderpilot.api.dto;

import com.orderpilot.api.dto.ValidationEngineDtos.ProductCandidate;
import com.orderpilot.domain.product.CompatibilityStatus;
import com.orderpilot.domain.product.CustomerPreferenceStatus;
import com.orderpilot.domain.product.ProductMatchConfidence;
import com.orderpilot.domain.product.ProductMatchType;
import com.orderpilot.domain.product.SubstituteReason;
import com.orderpilot.domain.product.SubstituteRiskLevel;
import com.orderpilot.domain.validation.ValidationIssueType;
import com.orderpilot.domain.validation.ValidationSeverity;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-09A Product Intelligence + Substitution Foundation DTOs.
 *
 * <p>All read-only, advisory views produced by {@code ProductIntelligenceService}. Nothing here is a
 * commercial decision: substitutes are suggestions, never approvals; compatibility is evidence, never
 * a guarantee. This is the foundation, not the final substitution engine.
 */
public final class ProductIntelligenceDtos {
  private ProductIntelligenceDtos() {}

  /** Requested vehicle/equipment fitment context (optional; absence never invalidates an exact SKU). */
  public record VehicleContext(String make, String model, Integer year, String configuration) {
    public boolean isPresent() {
      return (make != null && !make.isBlank()) || (model != null && !model.isBlank()) || year != null;
    }

    public static VehicleContext empty() {
      return new VehicleContext(null, null, null, null);
    }
  }

  /** Result of resolving a requested item to a product (or NONE), with compatibility + issues. */
  public record ProductResolutionResult(
      ProductMatchType matchType,
      ProductMatchConfidence confidence,
      UUID productId,
      ProductCandidate candidate,
      boolean ambiguous,
      boolean unmatched,
      CompatibilityStatus compatibilityStatus,
      List<CompatibilityEvidence> compatibilityEvidence,
      List<ProductIntelligenceIssue> issues) {}

  /** Lightweight, line-index-agnostic issue emitted by product intelligence. */
  public record ProductIntelligenceIssue(
      ValidationIssueType type,
      ValidationSeverity severity,
      String message) {}

  /** Safe compatibility evidence pointer (no fabricated data). */
  public record CompatibilityEvidence(
      String compatibleType,
      String make,
      String model,
      Integer yearFrom,
      Integer yearTo,
      String configuration,
      String riskLevel,
      String source) {}

  /** Ranked substitute candidate with explanation. Advisory only — never auto-approved. */
  public record SubstituteCandidate(
      UUID substituteProductId,
      String sku,
      String displayName,
      UUID sourceProductId,
      List<SubstituteReason> reasons,
      SubstituteRiskLevel riskLevel,
      boolean requiresApproval,
      boolean blocked,
      String stockStatus,
      BigDecimal availableQuantity,
      CompatibilityStatus compatibilityStatus,
      List<CompatibilityEvidence> compatibilityEvidence,
      CustomerPreferenceStatus customerPreferenceStatus,
      String explanation) {}
}
