package com.orderpilot.application.services.runtime;

import java.util.Map;
import java.util.UUID;

/**
 * OP-CAP-16F Runtime Unit Estimation — a request to estimate the work size (in usage units) of a
 * high-cost operation, using <b>only cheap metadata already available at the boundary</b>.
 *
 * <p>All "known*" fields are optional and nullable. The estimator never parses documents, reads
 * object storage, calls AI, or runs queries to fill them — callers pass what they already cheaply
 * know (a stored row/page count, a known file size, etc.) and the estimator falls back to 1 when
 * nothing useful is present. {@code metadata} is a small reserved bag of safe tokens (unused by the
 * default estimator).
 *
 * @param tenantId tenant scope (carried for context; the estimator itself is tenant-agnostic and O(1))
 * @param operationType the operation being sized
 * @param featureType the gated feature (carried for context)
 * @param knownPageCount stored document page count, if known
 * @param knownLineCount stored line count, if known
 * @param knownRowCount staged/candidate row count, if known
 * @param knownSizeBytes stored payload size in bytes, if known
 * @param knownMessageCount message batch size, if known
 * @param metadata reserved safe-token bag (nullable)
 */
public record RuntimeUnitEstimateRequest(
    UUID tenantId,
    RuntimeOperationType operationType,
    RuntimeFeatureType featureType,
    Integer knownPageCount,
    Integer knownLineCount,
    Integer knownRowCount,
    Long knownSizeBytes,
    Integer knownMessageCount,
    Map<String, String> metadata) {

  /** Document extraction: page count → size → line count → 1. */
  public static RuntimeUnitEstimateRequest forDocumentExtraction(
      UUID tenantId, Integer knownPageCount, Long knownSizeBytes, Integer knownLineCount) {
    return new RuntimeUnitEstimateRequest(
        tenantId,
        RuntimeOperationType.AI_DOCUMENT_EXTRACTION,
        RuntimeFeatureType.AI_DOCUMENT_EXTRACTION,
        knownPageCount,
        knownLineCount,
        null,
        knownSizeBytes,
        null,
        null);
  }

  /** Bulk import: staged row count → file size → 1. */
  public static RuntimeUnitEstimateRequest forBulkImport(
      UUID tenantId, Integer knownRowCount, Long knownSizeBytes) {
    return new RuntimeUnitEstimateRequest(
        tenantId,
        RuntimeOperationType.BULK_IMPORT,
        RuntimeFeatureType.BULK_IMPORT,
        null,
        null,
        knownRowCount,
        knownSizeBytes,
        null,
        null);
  }

  /** Reconciliation: candidate/pair count → 1. */
  public static RuntimeUnitEstimateRequest forReconciliation(UUID tenantId, Integer knownItemCount) {
    return new RuntimeUnitEstimateRequest(
        tenantId,
        RuntimeOperationType.RECONCILIATION_RUN,
        RuntimeFeatureType.RECONCILIATION_RUN,
        null,
        null,
        knownItemCount,
        null,
        null,
        null);
  }

  /** Report/export: expected row count → 1. */
  public static RuntimeUnitEstimateRequest forReport(UUID tenantId, Integer knownRowCount) {
    return new RuntimeUnitEstimateRequest(
        tenantId,
        RuntimeOperationType.REPORT_GENERATED,
        RuntimeFeatureType.REPORT_EXPORT,
        null,
        null,
        knownRowCount,
        null,
        null,
        null);
  }

  /** AI explanation/summary: known line count → message/item count → 1. */
  public static RuntimeUnitEstimateRequest forExplanation(
      UUID tenantId, Integer knownLineCount, Integer knownMessageCount) {
    return new RuntimeUnitEstimateRequest(
        tenantId,
        RuntimeOperationType.AI_VALIDATION_EXPLANATION,
        RuntimeFeatureType.AI_VALIDATION_EXPLANATION,
        null,
        knownLineCount,
        null,
        null,
        knownMessageCount,
        null);
  }

  /** No cheap metadata known → estimator returns 1. */
  public static RuntimeUnitEstimateRequest fallback(
      UUID tenantId, RuntimeOperationType operationType, RuntimeFeatureType featureType) {
    return new RuntimeUnitEstimateRequest(
        tenantId, operationType, featureType, null, null, null, null, null, null);
  }
}
