package com.orderpilot.api.dto;

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
}
