package com.orderpilot.application.services.validation;

import com.orderpilot.api.dto.ValidationReviewCommandDtos;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.LineageTimelineEntry;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRemediationLineageAction;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRemediationLineageDetail;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRemediationLineageLine;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRemediationLineageUnattachedAction;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.validation.ApprovalRequirement;
import com.orderpilot.domain.validation.ApprovalRequirementRepository;
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import com.orderpilot.domain.workspace.DraftOrder;
import com.orderpilot.domain.workspace.DraftOrderLine;
import com.orderpilot.domain.workspace.DraftOrderLineRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteLine;
import com.orderpilot.domain.workspace.DraftQuoteLineRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.OperatorAction;
import com.orderpilot.domain.workspace.OperatorActionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-15H — read-only remediation lineage DETAIL for one review-origin draft.
 *
 * <p>Makes the OP-CAP-15G queue remediation summary explainable. For a single Draft Quote/Order created
 * from a validation review, this resolves — per draft line — the structured {@link OperatorAction} lineage
 * written by OP-CAP-14C: line-item corrections, validation-issue resolutions and approval requests. Actions
 * that exist for the originating run but cannot be mapped to a drafted line (e.g. a resolution on a
 * non-drafted line, or a run-level approval) are reported separately as unattached.
 *
 * <p>Trust boundary: read-only. Tenant-scoped (a missing/foreign-tenant draft is a bounded 404). All
 * lineage is derived ONLY from records with stable ids (draft line source ids, validation issue ids,
 * approval requirement ids, structured OperatorAction target ids) using a fixed set of bounded batch
 * queries — never free-text/notes parsing, never a raw AI payload. Creates no draft, emits no audit,
 * writes nothing, and never triggers an external/ERP/1C/connector action.
 */
@Service
public class ValidationReviewDraftRemediationLineageService {
  // Canonical OperatorAction tokens written by OP-CAP-14C (mirrored here for read-side lineage only).
  private static final String TARGET_LINE = "EXTRACTED_LINE_ITEM";
  private static final String TARGET_ISSUE = "VALIDATION_ISSUE";
  private static final String TARGET_APPROVAL = "APPROVAL_REQUIREMENT";
  private static final String ACTION_LINE_CORRECTION = "VALIDATION_REVIEW_LINE_ITEM_CORRECTED";
  private static final String ACTION_ISSUE_RESOLUTION = "VALIDATION_REVIEW_ISSUE_RESOLVED";
  private static final String ACTION_APPROVAL_REQUEST = "VALIDATION_REVIEW_APPROVAL_REQUESTED";

  private final DraftQuoteRepository draftQuoteRepository;
  private final DraftOrderRepository draftOrderRepository;
  private final DraftQuoteLineRepository draftQuoteLineRepository;
  private final DraftOrderLineRepository draftOrderLineRepository;
  private final OperatorActionRepository operatorActionRepository;
  private final ValidationIssueRepository validationIssueRepository;
  private final ApprovalRequirementRepository approvalRequirementRepository;

  public ValidationReviewDraftRemediationLineageService(
      DraftQuoteRepository draftQuoteRepository,
      DraftOrderRepository draftOrderRepository,
      DraftQuoteLineRepository draftQuoteLineRepository,
      DraftOrderLineRepository draftOrderLineRepository,
      OperatorActionRepository operatorActionRepository,
      ValidationIssueRepository validationIssueRepository,
      ApprovalRequirementRepository approvalRequirementRepository) {
    this.draftQuoteRepository = draftQuoteRepository;
    this.draftOrderRepository = draftOrderRepository;
    this.draftQuoteLineRepository = draftQuoteLineRepository;
    this.draftOrderLineRepository = draftOrderLineRepository;
    this.operatorActionRepository = operatorActionRepository;
    this.validationIssueRepository = validationIssueRepository;
    this.approvalRequirementRepository = approvalRequirementRepository;
  }

  /** Resolved draft header + bounded per-line view, kind-agnostic so the lineage logic is shared. */
  private record DraftLineView(UUID draftLineId, UUID sourceLineItemId, int lineNumber, String sku, String description, BigDecimal quantity, String uom) {}

  @Transactional(readOnly = true)
  public ValidationReviewDraftRemediationLineageDetail remediationLineage(String draftKind, UUID draftId) {
    UUID tenantId = TenantContext.requireTenantId();
    String kind = normalizeKind(draftKind);

    UUID validationRunId;
    UUID sourceExceptionCaseId;
    String workspacePath;
    List<DraftLineView> draftLines;
    if ("QUOTE".equals(kind)) {
      DraftQuote quote = draftQuoteRepository.findByIdAndTenantId(draftId, tenantId)
          .orElseThrow(() -> new NotFoundException("draft_quote_not_found"));
      validationRunId = quote.getSourceValidationRunId();
      sourceExceptionCaseId = quote.getSourceExceptionCaseId();
      workspacePath = "/workspace/draft-quotes/" + quote.getId();
      draftLines = draftQuoteLineRepository.findByTenantIdAndDraftQuoteId(tenantId, draftId).stream()
          .map(this::quoteLineView).toList();
    } else {
      DraftOrder order = draftOrderRepository.findByIdAndTenantId(draftId, tenantId)
          .orElseThrow(() -> new NotFoundException("draft_order_not_found"));
      validationRunId = order.getSourceValidationRunId();
      sourceExceptionCaseId = order.getSourceExceptionCaseId();
      workspacePath = "/workspace/draft-orders/" + order.getId();
      draftLines = draftOrderLineRepository.findByTenantIdAndDraftOrderId(tenantId, draftId).stream()
          .map(this::orderLineView).toList();
    }

    if (validationRunId == null) {
      // Not a review-origin draft — lineage cannot be derived. Safe, explicit, read-only.
      return new ValidationReviewDraftRemediationLineageDetail(kind, draftId, null, sourceExceptionCaseId,
          false, List.of(ValidationReviewCommandDtos.REMEDIATION_DRAFT_NOT_REVIEW_ORIGIN),
          draftLines.size(), 0, 0, 0, 0, 0, List.of(), List.of(), workspacePath, null, "DISABLED");
    }

    return buildLineage(tenantId, kind, draftId, validationRunId, sourceExceptionCaseId, workspacePath, draftLines);
  }

  private ValidationReviewDraftRemediationLineageDetail buildLineage(
      UUID tenantId, String kind, UUID draftId, UUID validationRunId, UUID sourceExceptionCaseId,
      String workspacePath, List<DraftLineView> draftLines) {

    Set<UUID> draftedSourceLines = new HashSet<>();
    draftLines.forEach(l -> { if (l.sourceLineItemId() != null) draftedSourceLines.add(l.sourceLineItemId()); });
    Set<UUID> runIds = Set.of(validationRunId);

    // Batch 1: line-item correction actions targeting any drafted source line -> sourceLineId -> actions.
    Map<UUID, List<OperatorAction>> correctionsByLine = groupByTarget(
        batchActions(tenantId, TARGET_LINE, ACTION_LINE_CORRECTION, draftedSourceLines));

    // Batch 2: issues for the run -> issueId -> issue (line + status).
    Map<UUID, ValidationIssue> issuesById = new HashMap<>();
    for (ValidationIssue issue : validationIssueRepository.findByTenantIdAndValidationRunIdInOrderByCreatedAtAsc(tenantId, runIds)) {
      issuesById.put(issue.getId(), issue);
    }
    // Batch 3: issue-resolution actions on those issues -> issueId -> actions.
    Map<UUID, List<OperatorAction>> resolutionsByIssue = groupByTarget(
        batchActions(tenantId, TARGET_ISSUE, ACTION_ISSUE_RESOLUTION, issuesById.keySet()));

    // Batch 4: approvals for the run -> approvalId -> approval (line + status).
    Map<UUID, ApprovalRequirement> approvalsById = new HashMap<>();
    for (ApprovalRequirement approval : approvalRequirementRepository.findByTenantIdAndValidationRunIdInOrderByCreatedAtAsc(tenantId, runIds)) {
      approvalsById.put(approval.getId(), approval);
    }
    // Batch 5: approval-request actions on those approvals -> approvalId -> actions.
    Map<UUID, List<OperatorAction>> approvalsByApproval = groupByTarget(
        batchActions(tenantId, TARGET_APPROVAL, ACTION_APPROVAL_REQUEST, approvalsById.keySet()));

    // issue/approval -> drafted source line (only when their extracted line item is a drafted line).
    Map<UUID, List<UUID>> issuesByLine = new HashMap<>();
    issuesById.values().forEach(i -> {
      if (i.getExtractedLineItemId() != null && draftedSourceLines.contains(i.getExtractedLineItemId())) {
        issuesByLine.computeIfAbsent(i.getExtractedLineItemId(), k -> new ArrayList<>()).add(i.getId());
      }
    });
    Map<UUID, List<UUID>> approvalsByLine = new HashMap<>();
    approvalsById.values().forEach(a -> {
      if (a.getExtractedLineItemId() != null && draftedSourceLines.contains(a.getExtractedLineItemId())) {
        approvalsByLine.computeIfAbsent(a.getExtractedLineItemId(), k -> new ArrayList<>()).add(a.getId());
      }
    });

    Set<UUID> attachedCorrectionActionIds = new HashSet<>();
    Set<UUID> attachedResolutionActionIds = new HashSet<>();
    Set<UUID> attachedApprovalActionIds = new HashSet<>();
    Set<UUID> remediatedLines = new HashSet<>();
    int traceableLineCount = 0;

    List<ValidationReviewDraftRemediationLineageLine> lines = new ArrayList<>();
    for (DraftLineView line : draftLines) {
      UUID sourceLine = line.sourceLineItemId();
      List<String> lineLimitations = new ArrayList<>();
      if (sourceLine == null) {
        lineLimitations.add(ValidationReviewCommandDtos.REMEDIATION_DRAFT_LINE_SOURCE_LINE_MISSING);
        lines.add(new ValidationReviewDraftRemediationLineageLine(line.draftLineId(), null, false,
            line.lineNumber(), line.sku(), line.description(), line.quantity(), line.uom(),
            List.of(), List.of(), List.of(), lineLimitations, List.of()));
        continue;
      }
      traceableLineCount++;

      List<ValidationReviewDraftRemediationLineageAction> corrections = new ArrayList<>();
      for (OperatorAction a : correctionsByLine.getOrDefault(sourceLine, List.of())) {
        corrections.add(action(a, sourceLine, null, null, null));
        attachedCorrectionActionIds.add(a.getId());
      }
      List<ValidationReviewDraftRemediationLineageAction> resolutions = new ArrayList<>();
      for (UUID issueId : issuesByLine.getOrDefault(sourceLine, List.of())) {
        ValidationIssue issue = issuesById.get(issueId);
        for (OperatorAction a : resolutionsByIssue.getOrDefault(issueId, List.of())) {
          resolutions.add(action(a, sourceLine, issueId, null, issue == null ? null : issue.getStatus()));
          attachedResolutionActionIds.add(a.getId());
        }
      }
      List<ValidationReviewDraftRemediationLineageAction> approvals = new ArrayList<>();
      for (UUID approvalId : approvalsByLine.getOrDefault(sourceLine, List.of())) {
        ApprovalRequirement approval = approvalsById.get(approvalId);
        for (OperatorAction a : approvalsByApproval.getOrDefault(approvalId, List.of())) {
          approvals.add(action(a, sourceLine, null, approvalId, approval == null ? null : approval.getStatus()));
          attachedApprovalActionIds.add(a.getId());
        }
      }
      if (!corrections.isEmpty() || !resolutions.isEmpty() || !approvals.isEmpty()) {
        remediatedLines.add(sourceLine);
      }
      lines.add(new ValidationReviewDraftRemediationLineageLine(line.draftLineId(), sourceLine, true,
          line.lineNumber(), line.sku(), line.description(), line.quantity(), line.uom(),
          corrections, resolutions, approvals, List.of(), buildTimeline(corrections, resolutions, approvals)));
    }

    // Unattached: run-scoped structured actions that map to no drafted line (resolutions on non-drafted
    // issue lines, approvals on non-drafted/run-level lines).
    List<ValidationReviewDraftRemediationLineageUnattachedAction> unattached = new ArrayList<>();
    for (Map.Entry<UUID, List<OperatorAction>> entry : resolutionsByIssue.entrySet()) {
      ValidationIssue issue = issuesById.get(entry.getKey());
      UUID issueLine = issue == null ? null : issue.getExtractedLineItemId();
      if (issueLine != null && draftedSourceLines.contains(issueLine)) continue; // attached above
      for (OperatorAction a : entry.getValue()) {
        unattached.add(unattachedAction(a, ValidationReviewCommandDtos.LINEAGE_CATEGORY_ISSUE_RESOLUTION,
            ValidationReviewCommandDtos.REMEDIATION_UNATTACHED_ISSUE_RESOLUTION_ACTION));
      }
    }
    for (Map.Entry<UUID, List<OperatorAction>> entry : approvalsByApproval.entrySet()) {
      ApprovalRequirement approval = approvalsById.get(entry.getKey());
      UUID approvalLine = approval == null ? null : approval.getExtractedLineItemId();
      if (approvalLine != null && draftedSourceLines.contains(approvalLine)) continue; // attached above
      for (OperatorAction a : entry.getValue()) {
        unattached.add(unattachedAction(a, ValidationReviewCommandDtos.LINEAGE_CATEGORY_APPROVAL,
            ValidationReviewCommandDtos.REMEDIATION_UNATTACHED_APPROVAL_ACTION));
      }
    }
    unattached.sort(Comparator.comparing(ValidationReviewDraftRemediationLineageUnattachedAction::createdAt,
        Comparator.nullsLast(Comparator.naturalOrder())));

    List<String> detailLimitations = new ArrayList<>();
    if (draftedSourceLines.isEmpty() && !draftLines.isEmpty()) {
      detailLimitations.add(ValidationReviewCommandDtos.REMEDIATION_LINEAGE_MISSING);
    }
    boolean anyLineMissingSource = draftLines.stream().anyMatch(l -> l.sourceLineItemId() == null);
    if (anyLineMissingSource && !detailLimitations.contains(ValidationReviewCommandDtos.REMEDIATION_DRAFT_LINE_SOURCE_LINE_MISSING)) {
      detailLimitations.add(ValidationReviewCommandDtos.REMEDIATION_DRAFT_LINE_SOURCE_LINE_MISSING);
    }
    if (unattached.stream().anyMatch(u -> ValidationReviewCommandDtos.LINEAGE_CATEGORY_ISSUE_RESOLUTION.equals(u.category()))) {
      detailLimitations.add(ValidationReviewCommandDtos.REMEDIATION_UNATTACHED_ISSUE_RESOLUTION_ACTION);
    }
    if (unattached.stream().anyMatch(u -> ValidationReviewCommandDtos.LINEAGE_CATEGORY_APPROVAL.equals(u.category()))) {
      detailLimitations.add(ValidationReviewCommandDtos.REMEDIATION_UNATTACHED_APPROVAL_ACTION);
    }

    return new ValidationReviewDraftRemediationLineageDetail(kind, draftId, validationRunId, sourceExceptionCaseId,
        true, List.copyOf(detailLimitations), draftLines.size(), traceableLineCount, remediatedLines.size(),
        attachedCorrectionActionIds.size(), attachedResolutionActionIds.size(), attachedApprovalActionIds.size(),
        lines, unattached, workspacePath, "/validations/" + validationRunId + "/review", "DISABLED");
  }

  // --- helpers -----------------------------------------------------------------------------------

  private DraftLineView quoteLineView(DraftQuoteLine l) {
    return new DraftLineView(l.getId(), l.getSourceExtractedLineItemId(), l.getLineNumber(),
        l.getNormalizedSku(), l.getDescription(), l.getQuantity(), l.getUom());
  }

  private DraftLineView orderLineView(DraftOrderLine l) {
    return new DraftLineView(l.getId(), l.getSourceExtractedLineItemId(), l.getLineNumber(),
        null, l.getDescription(), l.getQuantity(), l.getUom());
  }

  private List<OperatorAction> batchActions(UUID tenantId, String targetType, String actionType, Set<UUID> targetIds) {
    if (targetIds.isEmpty()) return List.of();
    return operatorActionRepository.findByTenantIdAndTargetTypeAndActionTypeAndTargetIdIn(tenantId, targetType, actionType, targetIds);
  }

  private Map<UUID, List<OperatorAction>> groupByTarget(List<OperatorAction> actions) {
    Map<UUID, List<OperatorAction>> grouped = new HashMap<>();
    actions.stream()
        .sorted(Comparator.comparing(OperatorAction::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(a -> a.getId().toString()))
        .forEach(a -> grouped.computeIfAbsent(a.getTargetId(), k -> new ArrayList<>()).add(a));
    return grouped;
  }

  /**
   * OP-CAP-15I — flatten a line's attached structured actions into a deterministic timeline. Ordered by
   * {@code createdAt} ascending then {@code actionId}; empty (never null) when the line has no attached
   * actions. Only line-scoped structured actions reach here (field corrections are never attached), so the
   * timeline contains no un-line-scoped or free-text entries.
   */
  private List<LineageTimelineEntry> buildTimeline(
      List<ValidationReviewDraftRemediationLineageAction> corrections,
      List<ValidationReviewDraftRemediationLineageAction> resolutions,
      List<ValidationReviewDraftRemediationLineageAction> approvals) {
    List<LineageTimelineEntry> timeline = new ArrayList<>();
    corrections.forEach(a -> timeline.add(toTimeline(ValidationReviewCommandDtos.LINEAGE_CATEGORY_CORRECTION, a)));
    resolutions.forEach(a -> timeline.add(toTimeline(ValidationReviewCommandDtos.LINEAGE_CATEGORY_ISSUE_RESOLUTION, a)));
    approvals.forEach(a -> timeline.add(toTimeline(ValidationReviewCommandDtos.LINEAGE_CATEGORY_APPROVAL, a)));
    timeline.sort(Comparator.comparing(LineageTimelineEntry::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(e -> e.actionId() == null ? "" : e.actionId().toString()));
    return List.copyOf(timeline);
  }

  private LineageTimelineEntry toTimeline(String category, ValidationReviewDraftRemediationLineageAction a) {
    return new LineageTimelineEntry(category, a.actionType(), a.operatorActionId(),
        a.relatedLineItemId(), a.relatedIssueId(), a.relatedApprovalRequirementId(),
        a.status(), a.summary(), a.createdAt());
  }

  private ValidationReviewDraftRemediationLineageAction action(OperatorAction a, UUID lineItemId, UUID issueId, UUID approvalId, String status) {
    return new ValidationReviewDraftRemediationLineageAction(a.getId(), a.getActionType(), a.getTargetType(),
        a.getTargetId(), lineItemId, issueId, approvalId, status, a.getCreatedAt(), a.getMessage());
  }

  private ValidationReviewDraftRemediationLineageUnattachedAction unattachedAction(OperatorAction a, String category, String limitation) {
    return new ValidationReviewDraftRemediationLineageUnattachedAction(a.getId(), a.getActionType(), a.getTargetType(),
        a.getTargetId(), category, limitation, a.getCreatedAt(), a.getMessage());
  }

  private String normalizeKind(String draftKind) {
    if (draftKind == null || draftKind.isBlank()) {
      throw new IllegalArgumentException("invalid_draft_kind");
    }
    String kind = draftKind.trim().toUpperCase(Locale.ROOT);
    if (!"QUOTE".equals(kind) && !"ORDER".equals(kind)) {
      throw new IllegalArgumentException("invalid_draft_kind");
    }
    return kind;
  }
}
