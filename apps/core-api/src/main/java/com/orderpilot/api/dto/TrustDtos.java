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
}
