package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * Bounded, read-only trust DTOs. These expose only the deterministic risk decision, bounded size
 * metadata, and the signal taxonomy with bounded evidence — never raw document text, large OCR text,
 * extracted values, or counterparty identity values.
 */
public final class TrustDtos {
  private TrustDtos() {}

  public record DocumentTrustSignalView(
      UUID id,
      String signalCode,
      String severity,
      String fieldKey,
      Integer pageNumber,
      String evidenceRef,
      String explanation,
      Instant createdAt) {}

  public record DocumentTrustRunView(
      UUID id,
      UUID sourceDocumentId,
      UUID validationRunId,
      String riskLevel,
      int riskScore,
      String decisionState,
      boolean requiresHumanReview,
      boolean blocksAutomation,
      boolean duplicateDetected,
      int signalCount,
      Long fileSizeBytes,
      Integer pageCount,
      Instant createdAt,
      List<DocumentTrustSignalView> signals) {}

  // ---------------------------------------------------------------------------
  // OP-CAP-17B Counterparty Trust Profile Foundation — read-only views.
  // Never expose bank fingerprint/hash, raw evidence, account numbers, or internal notes.
  // ---------------------------------------------------------------------------

  public record CounterpartyTrustCounts(
      long totalDocumentCount,
      long highRiskDocumentCount,
      long criticalRiskDocumentCount,
      long manualReviewCount,
      long approvedOverrideCount,
      long rejectedDocumentCount,
      long disputedCount,
      long bankAccountChangeCount) {}

  public record CounterpartyTrustSignalView(
      String signalCode,
      String severity,
      String explanation,
      String sourceType,
      Instant createdAt) {}

  public record CounterpartyTrustSnapshotView(
      int trustScore,
      String trustTier,
      String riskLevel,
      String reasonSummary,
      String sourceType,
      Instant createdAt) {}

  public record CounterpartyTrustProfileView(
      UUID counterpartyId,
      int trustScore,
      String trustTier,
      int documentReliabilityScore,
      int paymentReliabilityScore,
      int orderPatternScore,
      String lastRiskLevel,
      CounterpartyTrustCounts counts,
      List<CounterpartyTrustSignalView> recentSignals,
      List<CounterpartyTrustSnapshotView> recentSnapshots) {}

  // ---------------------------------------------------------------------------
  // OP-CAP-17D Trust Risk Decision Engine — deterministic risk decision surface.
  // Never expose raw document/OCR/prompt text, bank credentials, account numbers, or secrets.
  // ---------------------------------------------------------------------------

  /** Request to evaluate a deterministic risk decision for one business subject. */
  public record TrustRiskEvaluationRequest(
      String subjectType,
      UUID subjectId,
      UUID documentTrustRunId,
      UUID counterpartyId,
      UUID paymentObligationId,
      UUID validationRunId,
      BigDecimal transactionAmount,
      String currency,
      String businessAction,
      String idempotencyKey) {}

  public record TrustRiskSignalContributionView(
      UUID id,
      String sourceType,
      UUID sourceId,
      String signalCode,
      String severity,
      BigDecimal confidence,
      int weight,
      int contributionScore,
      String forcedLevel,
      String explanation,
      String evidenceRef,
      Instant createdAt) {}

  public record TrustApprovalRequirementView(
      UUID id,
      String requiredAction,
      String requiredPermissionCode,
      String requiredRoleCode,
      String reasonCode,
      String status,
      Instant createdAt,
      Instant satisfiedAt) {}

  public record TrustDecisionOverrideView(
      UUID id,
      String previousRiskLevel,
      String newRiskLevel,
      String previousAction,
      String newAction,
      String reason,
      UUID overriddenBy,
      Instant overriddenAt) {}

  public record TrustRiskDecisionView(
      UUID id,
      String subjectType,
      UUID subjectId,
      UUID documentTrustRunId,
      UUID counterpartyId,
      UUID paymentObligationId,
      UUID validationRunId,
      String riskLevel,
      int riskScore,
      String action,
      boolean humanReviewRequired,
      boolean blocking,
      int signalCount,
      String reasonSummary,
      String status,
      Instant createdAt,
      Instant updatedAt,
      List<TrustRiskSignalContributionView> contributions,
      List<TrustApprovalRequirementView> approvalRequirements,
      List<TrustDecisionOverrideView> overrides) {}

  /** Compact response returned from an evaluate call (header + bounded reason codes). */
  public record TrustRiskEvaluationResponse(
      UUID id,
      String subjectType,
      UUID subjectId,
      String riskLevel,
      int riskScore,
      String action,
      boolean humanReviewRequired,
      boolean blocking,
      String reasonSummary,
      List<String> reasonCodes,
      List<TrustApprovalRequirementView> approvalRequirements) {}

  public record TrustDecisionOverrideRequest(
      String newRiskLevel,
      String newAction,
      String reason) {}
}
