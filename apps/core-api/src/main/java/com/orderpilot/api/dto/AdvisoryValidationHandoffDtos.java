package com.orderpilot.api.dto;

import java.util.UUID;

/**
 * OP-CAP-13A — bounded contract for handing a persisted AI-worker advisory extraction result into the
 * deterministic Core validation/risk workflow.
 *
 * <p>This DTO carries advisory provenance and the deterministic validation outcome only. It never
 * carries an executable/business-mutation surface, raw document/message bodies, secrets, or any
 * approved business record. {@code advisoryOnly} is always {@code true}: deterministic Core validation
 * and human/operator review remain the authority, and a handoff never creates a quote/order/inventory/
 * customer/price change.
 */
public final class AdvisoryValidationHandoffDtos {
  private AdvisoryValidationHandoffDtos() {}

  /** Stable handoff outcome tokens (no business action surface). */
  public static final String STATUS_ACCEPTED = "ACCEPTED";
  public static final String STATUS_FAILED_EXTRACTION = "FAILED_EXTRACTION";
  public static final String STATUS_UNSAFE_OUTPUT_REJECTED = "UNSAFE_OUTPUT_REJECTED";

  /**
   * Result of an advisory-extraction → deterministic-validation handoff.
   *
   * @param extractionResultId the advisory AI-worker extraction result that was handed off
   * @param sourceType preserved source context (channel/document type)
   * @param sourceId preserved source context (message/document id)
   * @param detectedIntent advisory intent hint (never authoritative)
   * @param handoffStatus ACCEPTED / FAILED_EXTRACTION / UNSAFE_OUTPUT_REJECTED
   * @param validationRunId deterministic validation run id, or {@code null} when nothing was validated
   * @param validationStatus deterministic run overall status, or {@code null} when not validated
   * @param routingRecommendation deterministic routing (AUTO_READY / NEEDS_OPERATOR_REVIEW / BLOCKED), or null
   * @param decomposedLineCount advisory line items decomposed into untrusted normalized rows
   * @param validationIssueCount total deterministic validation issues raised
   * @param blockingIssueCount CRITICAL/ERROR issues that block an auto-ready draft
   * @param approvalRequirementCount approval/review requirements raised by deterministic validation
   * @param duplicate true when this was an idempotent repeat of an already-validated handoff
   * @param advisoryOnly always {@code true}
   * @param failureReason bounded safe reason on a controlled failure, else {@code null}
   */
  public record AdvisoryValidationHandoffResult(
      UUID extractionResultId,
      String sourceType,
      UUID sourceId,
      String detectedIntent,
      String handoffStatus,
      UUID validationRunId,
      String validationStatus,
      String routingRecommendation,
      int decomposedLineCount,
      int validationIssueCount,
      int blockingIssueCount,
      int approvalRequirementCount,
      boolean duplicate,
      boolean advisoryOnly,
      String failureReason) {}
}
