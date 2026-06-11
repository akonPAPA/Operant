package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-14C — bounded request/response contract for the operator validation-review command layer.
 *
 * <p>These commands let an operator correct an advisory extracted field/line item, resolve a
 * validation issue, or raise an approval request — always through the tenant-scoped, permissioned,
 * audited Core command service. No request carries (and no response exposes) a raw AI advisory payload,
 * full document/message body, prompt text, secret/token or stack trace. No command creates a final
 * quote/order or writes ERP/1C/connector/master data.
 */
public final class ValidationReviewCommandDtos {
  private ValidationReviewCommandDtos() {}

  /** Bounded reason / corrected-value length cap. */
  public static final int MAX_REASON = 512;
  public static final int MAX_VALUE = 512;
  public static final int MAX_UOM = 16;

  public static final String TARGET_FIELD = "FIELD";
  public static final String TARGET_LINE_ITEM = "LINE_ITEM";

  public static final String RESOLUTION_RESOLVED = "RESOLVED";
  public static final String RESOLUTION_IGNORED = "IGNORED";
  public static final String RESOLUTION_ESCALATED = "ESCALATED";

  /**
   * Operator correction of a single advisory field or line item.
   *
   * @param targetType FIELD or LINE_ITEM (strict allowlist — any other value is rejected)
   * @param targetId the extracted field id (FIELD) or extracted line item id (LINE_ITEM)
   * @param correctedValue corrected normalized value for a FIELD target
   * @param correctedQuantity corrected normalized quantity for a LINE_ITEM target (string, must be > 0)
   * @param correctedUom corrected normalized UOM for a LINE_ITEM target
   * @param reason bounded operator reason (audited)
   * @param actorUserId optional actor (audit metadata)
   * @param clientRequestId optional idempotency/correlation key echoed back
   */
  public record ValidationReviewCorrectionRequest(
      String targetType,
      UUID targetId,
      String correctedValue,
      String correctedQuantity,
      String correctedUom,
      String reason,
      UUID actorUserId,
      String clientRequestId) {}

  /**
   * Operator decision on a validation issue.
   *
   * @param resolution RESOLVED / IGNORED / ESCALATED
   * @param reason bounded operator reason (audited)
   * @param correctionActionId optional id of the correction action that resolved the issue
   * @param actorUserId optional actor (audit metadata)
   * @param clientRequestId optional idempotency/correlation key echoed back
   */
  public record ValidationIssueResolutionRequest(
      String resolution,
      String reason,
      UUID correctionActionId,
      UUID actorUserId,
      String clientRequestId) {}

  /**
   * Minimal approval request for a risky correction/decision (reuses existing approval infrastructure).
   *
   * @param extractedLineItemId optional line item the approval is about
   * @param requirementType bounded approval requirement type token
   * @param reason bounded operator reason (audited)
   * @param actorUserId optional actor (audit metadata)
   */
  public record ValidationApprovalRequestCommand(
      UUID extractedLineItemId,
      String requirementType,
      String reason,
      UUID actorUserId) {}

  /** Maximum length of an operator note attached at draft creation. */
  public static final int MAX_OPERATOR_NOTE = 1000;

  /**
   * OP-CAP-15B — bounded request for creating an internal draft from a validation review.
   *
   * @param actorUserId optional actor (audit metadata)
   * @param selectedLineIds optional subset of extracted line item ids; {@code null}/omitted = all
   *     eligible validated lines (OP-CAP-15A behavior); an explicit empty list is rejected (400)
   * @param operatorNote optional bounded operator note (trimmed, max {@value #MAX_OPERATOR_NOTE})
   */
  public record ValidationReviewDraftRequest(
      UUID actorUserId,
      List<UUID> selectedLineIds,
      String operatorNote) {}

  /**
   * OP-CAP-15B — bounded read-only draft visibility for a validation review. Internal draft only.
   *
   * @param exists whether a draft already exists for this validation run
   * @param draftType QUOTE / ORDER / null
   * @param draftId the existing draft id, or null
   * @param workspacePath frontend route to the existing draft, or null
   * @param sourceValidationRunId the validation run this status was queried for
   * @param sourceExceptionCaseId the source case behind the draft, or null
   * @param lineCount draft line count when a draft exists, else 0
   * @param createdAt draft creation timestamp, or null
   * @param externalExecution always DISABLED
   */
  public record ValidationReviewDraftStatus(
      boolean exists,
      String draftType,
      UUID draftId,
      String workspacePath,
      UUID sourceValidationRunId,
      UUID sourceExceptionCaseId,
      int lineCount,
      Instant createdAt,
      String externalExecution) {}

  /**
   * OP-CAP-15A — bounded result of creating an internal Draft Quote / Draft Order from a validation
   * review. Internal draft only: never a final/approved order, never an ERP/1C/connector write.
   * {@code externalExecution} is always {@code DISABLED}.
   *
   * @param draftId the created (or idempotently existing) internal draft id
   * @param draftType QUOTE or ORDER
   * @param draftStatus bounded draft status token
   * @param sourceReviewId the source validation run the draft was prepared from
   * @param createdLineCount number of draft lines built from the validated review
   * @param unresolvedBlockingIssueCount open CRITICAL/ERROR validation issues on the run
   * @param unresolvedWarningIssueCount open WARNING validation issues on the run
   * @param approvalRequired true when an open approval requirement exists on the run
   * @param created true when this call created the draft
   * @param alreadyExisted true when an idempotent replay returned the existing draft
   * @param externalExecution always DISABLED
   * @param nextAction declarative next-action hint
   * @param nextRoute frontend route to the created draft, when one exists
   */
  public record ValidationReviewDraftResult(
      UUID draftId,
      String draftType,
      String draftStatus,
      UUID sourceReviewId,
      int createdLineCount,
      int unresolvedBlockingIssueCount,
      int unresolvedWarningIssueCount,
      boolean approvalRequired,
      boolean created,
      boolean alreadyExisted,
      String externalExecution,
      String nextAction,
      String nextRoute) {}

  /**
   * Bounded result of a validation-review command. Carries ids, action/decision status, approval flag
   * and a safe message only — never a raw payload.
   */
  public record ValidationReviewActionResult(
      UUID actionId,
      UUID validationRunId,
      String targetType,
      UUID targetId,
      String actionType,
      String actionStatus,
      boolean approvalRequired,
      UUID approvalRequestId,
      UUID resolvedIssueId,
      String issueResolution,
      UUID createdBy,
      Instant createdAt,
      String clientRequestId,
      String message) {}
}
