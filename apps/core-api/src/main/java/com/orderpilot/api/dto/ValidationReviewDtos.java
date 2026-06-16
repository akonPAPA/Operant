package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-14A — bounded read-only operator review contract.
 *
 * <p>These DTOs compose already-persisted deterministic validation artifacts (extraction result,
 * validation run, extracted fields/lines, validation issues, approval requirements, source evidence
 * and bounded audit metadata) into a single frontend-friendly payload for the operator review
 * workspace consumed by OP-CAP-14B.
 *
 * <p>Safety: this contract is advisory/read-only. It never carries the raw AI-worker advisory JSON,
 * full document/message bodies, prompt text, provider secrets, tokens, connector credentials or
 * stack traces. {@code advisoryOnly} is always {@code true}. Exposing this payload creates or mutates
 * no quote/order/customer/inventory/price/discount/margin/connector/ERP/1C data; the {@code allowedActions}
 * are declarative hints only — no executable command is produced here.
 */
public final class ValidationReviewDtos {
  private ValidationReviewDtos() {}

  /** Bounded source-evidence snippet cap (defensive — evidence is already a snippet, not a full body). */
  public static final int MAX_SNIPPET_LENGTH = 280;

  /** Maximum number of audit timeline rows surfaced (bounded metadata only). */
  public static final int MAX_AUDIT_ROWS = 50;

  /** Stable declarative next-action hint tokens (no executable surface). */
  public static final String ACTION_REVIEW_FIELDS = "REVIEW_FIELDS";
  public static final String ACTION_FIX_LINE_ITEM = "FIX_LINE_ITEM";
  public static final String ACTION_APPROVE_SUBSTITUTE = "APPROVE_SUBSTITUTE_REQUIRES_PERMISSION";
  public static final String ACTION_RERUN_VALIDATION = "RERUN_VALIDATION_ALLOWED";
  public static final String ACTION_CREATE_DRAFT_QUOTE = "CREATE_DRAFT_QUOTE_NOT_IMPLEMENTED";

  /** Top-level review detail payload. */
  public record ValidationReviewDetailResponse(
      ExtractionReviewSummary extraction,
      ValidationRunReviewSummary validationRun,
      List<ExtractedFieldReviewItem> fields,
      List<ExtractedLineItemReviewItem> lineItems,
      List<ValidationIssueReviewItem> issues,
      List<SourceEvidenceReviewItem> sourceEvidence,
      List<AuditTimelineItem> auditTimeline,
      List<AllowedReviewAction> allowedActions,
      boolean advisoryOnly) {}

  public record ExtractionReviewSummary(
      UUID extractionResultId,
      String sourceType,
      UUID sourceId,
      String detectedIntent,
      String documentType,
      String workerStatus,
      String validationStatus,
      BigDecimal overallConfidence,
      Instant createdAt,
      boolean advisoryOnly) {}

  public record ValidationRunReviewSummary(
      UUID validationRunId,
      String status,
      String overallStatus,
      String routingDecision,
      int blockingIssueCount,
      int warningReviewIssueCount,
      int approvalRequirementCount,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt) {}

  public record ExtractedFieldReviewItem(
      UUID fieldId,
      String fieldName,
      String extractedValue,
      String normalizedValue,
      String valueType,
      BigDecimal confidence,
      String validationStatus,
      UUID sourceEvidenceId,
      List<UUID> issueIds) {}

  public record ExtractedLineItemReviewItem(
      UUID lineItemId,
      int lineNumber,
      String rawSku,
      UUID matchedProductId,
      String matchStatus,
      String description,
      BigDecimal quantity,
      String uom,
      BigDecimal confidence,
      String validationStatus,
      UUID sourceEvidenceId,
      List<UUID> issueIds) {}

  public record ValidationIssueReviewItem(
      UUID issueId,
      String severity,
      String code,
      String message,
      String targetType,
      UUID targetId,
      Integer targetLineNumber,
      boolean blocking,
      String status) {}

  public record SourceEvidenceReviewItem(
      UUID sourceEvidenceId,
      String evidenceType,
      Integer pageNumber,
      Integer startOffset,
      Integer endOffset,
      String snippet) {}

  public record AuditTimelineItem(
      UUID actorId,
      String action,
      String entityType,
      String entityId,
      Instant occurredAt) {}

  public record AllowedReviewAction(
      String action,
      boolean enabled,
      String requiredPermission) {}
}
