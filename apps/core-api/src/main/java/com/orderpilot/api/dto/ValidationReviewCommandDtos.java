package com.orderpilot.api.dto;

import java.math.BigDecimal;
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
   * @param clientRequestId optional idempotency/correlation key echoed back
   */
  public record ValidationReviewCorrectionRequest(
      String targetType,
      UUID targetId,
      String correctedValue,
      String correctedQuantity,
      String correctedUom,
      String reason,
      String clientRequestId) {}

  /**
   * Operator decision on a validation issue.
   *
   * @param resolution RESOLVED / IGNORED / ESCALATED
   * @param reason bounded operator reason (audited)
   * @param correctionActionId optional id of the correction action that resolved the issue
   * @param clientRequestId optional idempotency/correlation key echoed back
   */
  public record ValidationIssueResolutionRequest(
      String resolution,
      String reason,
      UUID correctionActionId,
      String clientRequestId) {}

  /**
   * Minimal approval request for a risky correction/decision (reuses existing approval infrastructure).
   *
   * @param extractedLineItemId optional line item the approval is about
   * @param requirementType bounded approval requirement type token
   * @param reason bounded operator reason (audited)
   */
  public record ValidationApprovalRequestCommand(
      UUID extractedLineItemId,
      String requirementType,
      String reason) {}

  /** Maximum length of an operator note attached at draft creation. */
  public static final int MAX_OPERATOR_NOTE = 1000;

  /**
   * OP-CAP-15B — bounded request for creating an internal draft from a validation review.
   *
   * @param selectedLineIds optional subset of extracted line item ids; {@code null}/omitted = all
   *     eligible validated lines (OP-CAP-15A behavior); an explicit empty list is rejected (400)
   * @param operatorNote optional bounded operator note (trimmed, max {@value #MAX_OPERATOR_NOTE})
   */
  public record ValidationReviewDraftRequest(
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

  // ---------------------------------------------------------------------------------------------
  // OP-CAP-15C — advisory per-line draftability hints + review-origin draft queue (read-only).
  // ---------------------------------------------------------------------------------------------

  /** Per-line draftability reason codes (advisory UI hints; the create endpoint stays authoritative). */
  public static final String REASON_LINE_READY = "LINE_READY";
  public static final String REASON_BLOCKING_ISSUE_UNRESOLVED = "BLOCKING_ISSUE_UNRESOLVED";
  public static final String REASON_WARNING_ISSUE_PRESENT = "WARNING_ISSUE_PRESENT";
  public static final String REASON_SKU_NOT_VALIDATED = "SKU_NOT_VALIDATED";
  public static final String REASON_QUANTITY_NOT_NORMALIZED = "QUANTITY_NOT_NORMALIZED";
  public static final String REASON_UOM_NOT_NORMALIZED = "UOM_NOT_NORMALIZED";
  public static final String REASON_PRODUCT_MATCH_MISSING = "PRODUCT_MATCH_MISSING";
  public static final String REASON_LINE_ALREADY_INCLUDED = "LINE_ALREADY_INCLUDED_IN_EXISTING_DRAFT";
  public static final String REASON_CASE_NOT_DRAFTABLE = "CASE_NOT_DRAFTABLE";
  public static final String REASON_NO_DRAFTABLE_LINE_ITEMS = "NO_DRAFTABLE_LINE_ITEMS";
  public static final String REASON_UNKNOWN_BLOCKER = "UNKNOWN_BLOCKER";

  /** Line/overall severity tokens. */
  public static final String SEVERITY_OK = "OK";
  public static final String SEVERITY_WARNING = "WARNING";
  public static final String SEVERITY_BLOCKED = "BLOCKED";

  /**
   * OP-CAP-15D — remediation type tokens mapping a draftability reason to an EXISTING OP-CAP-14C operator
   * action. These are advisory machine-readable hints; the frontend reuses the existing correction /
   * issue-resolution / approval controls — no new write path is introduced and the draft-create endpoint
   * stays the final authority.
   */
  public static final String REMEDIATION_RESOLVE_ISSUE = "RESOLVE_ISSUE";   // OP-CAP-14C issue resolution
  public static final String REMEDIATION_CORRECT_LINE = "CORRECT_LINE";     // OP-CAP-14C LINE_ITEM correction
  public static final String REMEDIATION_CORRECT_FIELD = "CORRECT_FIELD";   // OP-CAP-14C FIELD correction
  public static final String REMEDIATION_REQUEST_APPROVAL = "REQUEST_APPROVAL"; // OP-CAP-14C approval request
  public static final String REMEDIATION_VIEW_ISSUE = "VIEW_ISSUE";         // read-only: inspect the issue
  public static final String REMEDIATION_NONE = "NONE";

  /**
   * OP-CAP-15D — one advisory remediation step for a blocked/warning line. Maps a reason to an existing
   * OP-CAP-14C action with a stable, bounded, machine-readable target. Carries ids only — no raw payload,
   * no executable command. {@code targetIssueId}/{@code targetLineItemId} are tenant-scoped (the run was
   * already tenant-checked); a foreign-tenant caller never reaches this (404).
   */
  public record ValidationReviewLineRemediation(
      String reasonCode,
      String remediationType,
      UUID targetIssueId,
      UUID targetLineItemId,
      String recommendedAction) {}

  /** Review-origin draft queue pagination bounds. */
  public static final int DEFAULT_DRAFT_QUEUE_LIMIT = 25;
  public static final int MAX_DRAFT_QUEUE_LIMIT = 100;

  /**
   * OP-CAP-15C — advisory draftability for one validated line. Mirrors (does not replace) the canonical
   * readiness gate at line granularity. Uses validated/normalized values only — never raw AI values as
   * trusted business data. The POST create endpoint re-validates and remains the final authority.
   *
   * @param lineItemId the canonical extracted line item id (same id used by selectedLineIds)
   * @param draftable line-local advisory flag (no blocking line reason); overall create still gated by
   *     {@code caseDraftable} and one-draft-per-source-review ({@code draftExists})
   * @param severity OK / WARNING / BLOCKED
   * @param reasons bounded reason-code list
   * @param normalizedSku validated SKU/product display if available
   * @param normalizedQuantity normalized quantity if available
   * @param normalizedUom normalized UOM if available
   * @param hasBlockingIssue any open CRITICAL/ERROR issue on the line
   * @param hasWarningIssue any open WARNING issue on the line
   * @param alreadyDrafted line is already included in the existing draft for this review
   * @param sourceValidationRunId the run this line belongs to
   * @param sourceExceptionCaseId the source case behind an existing draft, or null
   */
  public record ValidationReviewLineDraftability(
      UUID lineItemId,
      int lineNumber,
      boolean draftable,
      String severity,
      List<String> reasons,
      String normalizedSku,
      BigDecimal normalizedQuantity,
      String normalizedUom,
      boolean hasBlockingIssue,
      boolean hasWarningIssue,
      boolean alreadyDrafted,
      UUID sourceValidationRunId,
      UUID sourceExceptionCaseId,
      List<ValidationReviewLineRemediation> remediations) {}

  /**
   * OP-CAP-15C — advisory draftability snapshot for a validation review. Read-only: creates no draft and
   * no ExceptionCase. {@code caseDraftable} reflects the canonical readiness gate (authoritative on POST).
   */
  public record ValidationReviewDraftabilityResponse(
      UUID sourceValidationRunId,
      UUID sourceExceptionCaseId,
      boolean draftExists,
      String existingDraftType,
      UUID existingDraftId,
      String existingWorkspacePath,
      boolean caseDraftable,
      String overallSeverity,
      List<String> caseBlockingReasons,
      int lineCount,
      int draftableLineCount,
      List<ValidationReviewLineDraftability> lines,
      String externalExecution) {}

  /**
   * OP-CAP-15G — read-only, derived-from-structured-records-only remediation lineage summary for one
   * review-origin draft. Counts are computed ONLY from tenant-scoped records with stable ids (draft line
   * source extracted-line-item ids, validation issue ids, structured {@code OperatorAction} target ids).
   * Free-text audit/notes are never parsed and raw note content is never exposed.
   *
   * @param available whether structured remediation lineage is derivable (draft lines carry source line
   *     ids); when false only {@code draftLineCount} is meaningful
   * @param draftLineCount number of lines in the internal draft
   * @param remediatedDraftLineCount drafted lines whose source line has structured remediation evidence
   *     (a line-item correction action targeting it, or an issue-resolution action on an issue whose
   *     extracted line item is that line)
   * @param correctionActionCount line-item correction actions targeting this draft's source lines
   * @param issueResolutionActionCount issue-resolution actions on issues whose extracted line item is one
   *     of this draft's source lines
   * @param approvalActionCount approval-request actions on approval requirements belonging to this draft's
   *     source review (line-linked to a source line, or run-level)
   * @param limitations bounded machine-readable limitation tokens (e.g. structured_action_lineage_missing)
   */
  public record ValidationReviewDraftRemediationSummary(
      boolean available,
      int draftLineCount,
      int remediatedDraftLineCount,
      int correctionActionCount,
      int issueResolutionActionCount,
      int approvalActionCount,
      List<String> limitations) {}

  /** OP-CAP-15G limitation token: draft lines carry no source line ids, so action lineage is not derivable. */
  public static final String REMEDIATION_LINEAGE_MISSING = "structured_action_lineage_missing";

  /**
   * OP-CAP-15I — compact, read-only remediation rollup for one review-origin draft queue row, so operators
   * see tenant-scoped remediation state without opening every detail page. Derived from the SAME structured,
   * tenant-scoped batch data as the OP-CAP-15G summary (no extra per-row query) — ids/counts/timestamp only,
   * never raw notes or AI payload.
   *
   * @param remediationLineageAvailable whether structured lineage is derivable (mirrors the 15G summary)
   * @param remediationActionCount attached correction + issue-resolution + approval actions
   * @param remediatedLineCount drafted lines with structured remediation evidence
   * @param traceableLineCount drafted lines carrying a source extracted-line-item id
   * @param limitationCodes bounded machine-readable limitation tokens (mirrors the 15G summary)
   * @param latestRemediationActionAt max {@code createdAt} across attached structured actions, or null
   */
  public record ValidationReviewDraftRemediationRollup(
      boolean remediationLineageAvailable,
      int remediationActionCount,
      int remediatedLineCount,
      int traceableLineCount,
      List<String> limitationCodes,
      Instant latestRemediationActionAt) {}

  /**
   * OP-CAP-15C — one review-origin draft in the lite queue. Internal draft only; never exposes raw
   * operator-note content ({@code operatorNotePresent} only). {@code externalExecution} is DISABLED.
   * OP-CAP-15G adds a read-only {@code remediationSummary} (structured lineage only). OP-CAP-15I adds a
   * compact {@code remediationRollup} derived from the same structured batch data.
   */
  public record ValidationReviewDraftQueueItem(
      UUID draftId,
      String draftType,
      UUID sourceValidationRunId,
      UUID sourceExceptionCaseId,
      String customerDisplay,
      String status,
      int lineCount,
      Instant createdAt,
      boolean operatorNotePresent,
      String workspacePath,
      String reviewPath,
      String externalExecution,
      ValidationReviewDraftRemediationSummary remediationSummary,
      ValidationReviewDraftRemediationRollup remediationRollup) {}

  /** OP-CAP-15C — bounded, tenant-scoped, paginated review-origin draft queue. */
  public record ValidationReviewDraftQueueResponse(
      List<ValidationReviewDraftQueueItem> items,
      int returned,
      int limit,
      int offset,
      String draftTypeFilter,
      String statusFilter) {}

  // ---------------------------------------------------------------------------------------------
  // OP-CAP-15H — read-only remediation lineage DETAIL for one review-origin draft. Makes the 15G
  // queue summary explainable: per draft line, the structured OperatorAction lineage (corrections,
  // issue resolutions, approvals) plus run-scoped structured actions that could not be attached to a
  // draft line. Derived ONLY from tenant-scoped records with stable ids — no free-text/notes parsing,
  // no raw AI payload. Read-only: creates no draft, emits no audit, writes nothing.
  // ---------------------------------------------------------------------------------------------

  /** OP-CAP-15H limitation token: the draft has no originating validation run, so lineage is not derivable. */
  public static final String REMEDIATION_DRAFT_NOT_REVIEW_ORIGIN = "draft_not_review_origin";
  /** OP-CAP-15H limitation token: a draft line carries no source extracted-line-item id (no line lineage). */
  public static final String REMEDIATION_DRAFT_LINE_SOURCE_LINE_MISSING = "draft_line_source_line_missing";
  /** OP-CAP-15H limitation token: an issue-resolution action exists for the run but maps to no drafted line. */
  public static final String REMEDIATION_UNATTACHED_ISSUE_RESOLUTION_ACTION = "unattached_issue_resolution_action";
  /** OP-CAP-15H limitation token: an approval action exists for the run but maps to no drafted line (or is run-level). */
  public static final String REMEDIATION_UNATTACHED_APPROVAL_ACTION = "unattached_approval_action";

  /** OP-CAP-15H unattached-action categories (stable machine-readable tokens). */
  public static final String LINEAGE_CATEGORY_CORRECTION = "CORRECTION";
  public static final String LINEAGE_CATEGORY_ISSUE_RESOLUTION = "ISSUE_RESOLUTION";
  public static final String LINEAGE_CATEGORY_APPROVAL = "APPROVAL";

  /**
   * OP-CAP-15H — one structured operator action in the remediation lineage. Carries stable ids, the
   * canonical 14C action type and a deterministic backend-authored message only — never a raw AI payload,
   * note body, prompt, secret or stack trace. {@code status} reflects the related issue/approval domain
   * status when applicable (else null).
   */
  public record ValidationReviewDraftRemediationLineageAction(
      UUID operatorActionId,
      String actionType,
      String targetType,
      UUID targetId,
      UUID relatedLineItemId,
      UUID relatedIssueId,
      UUID relatedApprovalRequirementId,
      String status,
      Instant createdAt,
      String summary) {}

  /**
   * OP-CAP-15I — one normalized entry in a draft line's remediation timeline. A flattened, deterministic
   * projection of the structured corrections / issue resolutions / approvals already attached to the line by
   * OP-CAP-15H, ordered by {@code createdAt} (then {@code actionId}). Carries stable ids, the canonical 14C
   * action token, the related issue/approval domain {@code status} and the deterministic backend-authored
   * {@code summary} only — never a raw operator note, raw AI payload, prompt or secret.
   *
   * @param category CORRECTION / ISSUE_RESOLUTION / APPROVAL
   */
  public record LineageTimelineEntry(
      String category,
      String actionType,
      UUID actionId,
      UUID targetLineItemId,
      UUID targetIssueId,
      UUID targetApprovalRequirementId,
      String status,
      String summary,
      Instant createdAt) {}

  /**
   * OP-CAP-15H — remediation lineage for one draft line. {@code sourceLineAvailable} is false when the
   * draft line carries no source extracted-line-item id (then action lists are empty and a
   * {@code draft_line_source_line_missing} limitation is present). SKU/description/quantity/uom mirror the
   * already-exposed bounded draft fields (order lines have no normalized SKU). OP-CAP-15I adds a normalized
   * {@code timeline} (deterministic, may be empty, never null) over the same attached structured actions.
   */
  public record ValidationReviewDraftRemediationLineageLine(
      UUID draftLineId,
      UUID sourceLineItemId,
      boolean sourceLineAvailable,
      int lineNumber,
      String sku,
      String description,
      BigDecimal quantity,
      String uom,
      List<ValidationReviewDraftRemediationLineageAction> correctionActions,
      List<ValidationReviewDraftRemediationLineageAction> issueResolutionActions,
      List<ValidationReviewDraftRemediationLineageAction> approvalActions,
      List<String> limitations,
      List<LineageTimelineEntry> timeline) {}

  /**
   * OP-CAP-15H — a run-scoped structured action that exists but could not be attached to a draft line
   * (e.g. an issue resolution whose issue line was not drafted, or a run-level approval). {@code category}
   * is one of CORRECTION / ISSUE_RESOLUTION / APPROVAL; {@code limitation} is the matching token.
   */
  public record ValidationReviewDraftRemediationLineageUnattachedAction(
      UUID operatorActionId,
      String actionType,
      String targetType,
      UUID targetId,
      String category,
      String limitation,
      Instant createdAt,
      String summary) {}

  /**
   * OP-CAP-15H — explainable, read-only remediation lineage detail for one review-origin draft. Counts
   * are over structured, tenant-scoped records with stable ids only. {@code available} is false only when
   * the detail cannot be safely produced at all (draft is not review-origin); partial lineage stays
   * {@code available=true} with machine-readable {@code limitations}. {@code externalExecution} is DISABLED.
   *
   * @param draftKind QUOTE or ORDER
   * @param validationRunId originating validation run, or null when not review-origin
   * @param traceableDraftLineCount draft lines carrying a source extracted-line-item id
   * @param remediatedDraftLineCount draft lines with at least one attached structured action
   * @param correctionActionCount distinct line-item correction actions attached to draft lines
   * @param issueResolutionActionCount distinct issue-resolution actions attached to draft lines
   * @param approvalActionCount distinct approval actions attached to draft lines
   * @param unattachedActions run-scoped structured actions that could not be attached to a draft line
   */
  public record ValidationReviewDraftRemediationLineageDetail(
      String draftKind,
      UUID draftId,
      UUID validationRunId,
      UUID sourceExceptionCaseId,
      boolean available,
      List<String> limitations,
      int draftLineCount,
      int traceableDraftLineCount,
      int remediatedDraftLineCount,
      int correctionActionCount,
      int issueResolutionActionCount,
      int approvalActionCount,
      List<ValidationReviewDraftRemediationLineageLine> lines,
      List<ValidationReviewDraftRemediationLineageUnattachedAction> unattachedActions,
      String workspacePath,
      String reviewPath,
      String externalExecution) {}

  // ---------------------------------------------------------------------------------------------
  // OP-CAP-15J — bounded recent-window remediation rollup TILE. A small, read-only, tenant-scoped
  // at-a-glance aggregate over the most recent drafts, built from the SAME structured per-draft
  // derivation as OP-CAP-15G/15I (no per-line detail recompute, no free-text parsing, no AI). It is a
  // bounded recent-window summary — NOT a full tenant analytics system and not an audit redesign.
  // ---------------------------------------------------------------------------------------------

  /** Recent remediation rollup window bounds (drafts inspected). */
  public static final int DEFAULT_RECENT_ROLLUP_LIMIT = 50;
  public static final int MAX_RECENT_ROLLUP_LIMIT = 100;
  /** Max drafts surfaced in {@code topLimitedDrafts}. */
  public static final int MAX_TOP_LIMITED_DRAFTS = 10;

  /**
   * OP-CAP-15J — one drafted entry inside the recent rollup, reusing the OP-CAP-15I per-draft rollup
   * numbers verbatim. Carries stable ids + counts + one nullable timestamp + machine-readable limitation
   * codes only — never raw notes or AI payload. {@code sourceValidationRunId} is null for a non-review-origin
   * draft (then lineage is unavailable with {@code draft_not_review_origin}).
   */
  public record ValidationReviewDraftRecentRemediationRollupItem(
      String draftKind,
      UUID draftId,
      UUID sourceValidationRunId,
      boolean remediationLineageAvailable,
      int remediationActionCount,
      int remediatedLineCount,
      int traceableLineCount,
      Instant latestRemediationActionAt,
      List<String> limitationCodes) {}

  /**
   * OP-CAP-15J — bounded recent-window remediation rollup for the review-draft workspace tile. Every count
   * is an aggregate of the same structured, tenant-scoped per-draft derivation used by the queue rows; no
   * unbounded tenant-wide scan, no N+1. Lists are never null. {@code latestRemediationActionAt} is the max
   * structured-action timestamp across the window (null when none). {@code limitationCodes} is the unique,
   * sorted union of per-draft limitation tokens. {@code topLimitedDrafts} is bounded and deterministically
   * sorted (latest action desc, then draftId).
   *
   * @param inspectedDraftCount drafts examined in the bounded recent window (both kinds)
   * @param reviewOriginDraftCount inspected drafts that originate from a validation review
   * @param lineageAvailableDraftCount inspected drafts with derivable structured lineage
   * @param lineageUnavailableDraftCount inspected drafts without derivable lineage (incl. non-review-origin)
   * @param draftLineCount draft lines across the review-origin inspected drafts
   */
  public record ValidationReviewDraftRecentRemediationRollupResponse(
      int inspectedDraftCount,
      int reviewOriginDraftCount,
      int lineageAvailableDraftCount,
      int lineageUnavailableDraftCount,
      int draftLineCount,
      int traceableDraftLineCount,
      int remediatedDraftLineCount,
      int remediationActionCount,
      int correctionActionCount,
      int issueResolutionActionCount,
      int approvalActionCount,
      Instant latestRemediationActionAt,
      List<String> limitationCodes,
      List<ValidationReviewDraftRecentRemediationRollupItem> topLimitedDrafts,
      int limit,
      String externalExecution) {}
}
