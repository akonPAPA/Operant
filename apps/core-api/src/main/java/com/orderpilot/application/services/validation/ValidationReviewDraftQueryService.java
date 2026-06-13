package com.orderpilot.application.services.validation;

import com.orderpilot.api.dto.ValidationReviewCommandDtos;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftQueueItem;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftQueueResponse;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRecentRemediationRollupItem;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRecentRemediationRollupResponse;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRemediationRollup;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRemediationSummary;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.validation.ApprovalRequirement;
import com.orderpilot.domain.validation.ApprovalRequirementRepository;
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import com.orderpilot.domain.workspace.DraftOrder;
import com.orderpilot.domain.workspace.DraftOrderLineRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteLineRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.OperatorAction;
import com.orderpilot.domain.workspace.OperatorActionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-15C/15G — lite, read-only queue of internal drafts created from validation reviews.
 *
 * <p>Lets operators/managers see which validation reviews already produced an internal Draft Quote/Order.
 * Tenant-scoped, paginated, sorted by {@code createdAt} desc. Read-only: no audit, no write, no external
 * execution. Never exposes raw operator-note content ({@code operatorNotePresent} only).
 *
 * <p>OP-CAP-15G adds a per-draft {@code remediationSummary} derived ONLY from tenant-scoped structured
 * records with stable ids — draft line source extracted-line-item ids, validation issue ids and structured
 * {@link OperatorAction} target ids written by OP-CAP-14C. Free-text audit/notes are never parsed. All
 * lineage is resolved with a fixed set of bounded batch queries (no N+1 over the page).
 */
@Service
public class ValidationReviewDraftQueryService {
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

  public ValidationReviewDraftQueryService(
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

  @Transactional(readOnly = true)
  public ValidationReviewDraftQueueResponse reviewDraftQueue(String draftTypeFilter, String statusFilter, Integer limit, Integer offset) {
    UUID tenantId = TenantContext.requireTenantId();
    String type = normalizeDraftType(draftTypeFilter);
    String status = statusFilter == null || statusFilter.isBlank() ? null : statusFilter.trim();
    int lim = clampLimit(limit);
    int off = offset == null || offset < 0 ? 0 : offset;
    // Fetch enough from each source to satisfy the requested page after the unified sort/skip.
    int fetch = Math.min(off + lim, off + ValidationReviewCommandDtos.MAX_DRAFT_QUEUE_LIMIT);
    PageRequest page = PageRequest.of(0, Math.max(fetch, 1));

    List<DraftQuote> quotes = type == null || "QUOTE".equals(type)
        ? draftQuoteRepository.findReviewOriginDrafts(tenantId, status, page) : List.of();
    List<DraftOrder> orders = type == null || "ORDER".equals(type)
        ? draftOrderRepository.findReviewOriginDrafts(tenantId, status, page) : List.of();
    List<UUID> quoteIds = quotes.stream().map(DraftQuote::getId).toList();
    List<UUID> orderIds = orders.stream().map(DraftOrder::getId).toList();

    Map<UUID, Integer> quoteLineCounts = lineCounts(draftQuoteLineRepository.countByDraftQuoteIds(tenantId, quoteIds));
    Map<UUID, Integer> orderLineCounts = lineCounts(draftOrderLineRepository.countByDraftOrderIds(tenantId, orderIds));

    Map<UUID, RemediationDerivation> derivations =
        buildRemediationDerivations(tenantId, quotes, orders, quoteIds, orderIds, quoteLineCounts, orderLineCounts);

    List<ValidationReviewDraftQueueItem> merged = new ArrayList<>();
    for (DraftQuote q : quotes) {
      RemediationDerivation d = derivations.get(q.getId());
      merged.add(new ValidationReviewDraftQueueItem(q.getId(), "QUOTE", q.getSourceValidationRunId(), q.getSourceExceptionCaseId(),
          q.getCustomerDisplayName(), q.getStatus(), quoteLineCounts.getOrDefault(q.getId(), 0), q.getCreatedAt(),
          notePresent(q.getNotes()), "/workspace/draft-quotes/" + q.getId(), reviewPath(q.getSourceValidationRunId()), "DISABLED",
          d.summary(), d.rollup()));
    }
    for (DraftOrder o : orders) {
      RemediationDerivation d = derivations.get(o.getId());
      merged.add(new ValidationReviewDraftQueueItem(o.getId(), "ORDER", o.getSourceValidationRunId(), o.getSourceExceptionCaseId(),
          null, o.getStatus(), orderLineCounts.getOrDefault(o.getId(), 0), o.getCreatedAt(),
          notePresent(o.getNotes()), "/workspace/draft-orders/" + o.getId(), reviewPath(o.getSourceValidationRunId()), "DISABLED",
          d.summary(), d.rollup()));
    }

    merged.sort(Comparator.comparing(ValidationReviewDraftQueueItem::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
    List<ValidationReviewDraftQueueItem> pageItems = merged.stream().skip(off).limit(lim).toList();
    return new ValidationReviewDraftQueueResponse(pageItems, pageItems.size(), lim, off, type, status);
  }

  /** OP-CAP-15J — one inspected draft in the bounded recent window (kind/id/run/createdAt only). */
  private record InspectedDraft(String kind, UUID draftId, UUID sourceValidationRunId, Instant createdAt) {}

  /**
   * OP-CAP-15J — bounded recent-window remediation rollup for the review-draft workspace tile. Inspects the
   * most recent drafts of both kinds (tenant-scoped, capped by {@code limit}), reuses the SAME structured
   * per-draft derivation as the queue rows for the review-origin subset, and aggregates. Non-review-origin
   * drafts contribute an explicit unavailable-lineage entry (no fabricated lineage). Read-only: no write, no
   * audit, no external execution. Bounded batch loads only — no per-draft query, no unbounded scan, no N+1.
   */
  @Transactional(readOnly = true)
  public ValidationReviewDraftRecentRemediationRollupResponse recentRemediationRollup(Integer limit) {
    UUID tenantId = TenantContext.requireTenantId();
    int lim = clampRollupLimit(limit);
    Pageable page = PageRequest.of(0, lim);

    List<DraftQuote> quotes = draftQuoteRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, page);
    List<DraftOrder> orders = draftOrderRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, page);

    // Unify both kinds, newest first (then draftId), and cap to the recent window.
    List<InspectedDraft> inspected = new ArrayList<>();
    quotes.forEach(q -> inspected.add(new InspectedDraft("QUOTE", q.getId(), q.getSourceValidationRunId(), q.getCreatedAt())));
    orders.forEach(o -> inspected.add(new InspectedDraft("ORDER", o.getId(), o.getSourceValidationRunId(), o.getCreatedAt())));
    inspected.sort(Comparator.comparing(InspectedDraft::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(i -> i.draftId().toString()));
    List<InspectedDraft> window = inspected.stream().limit(lim).toList();
    Set<UUID> windowIds = new HashSet<>();
    window.forEach(w -> windowIds.add(w.draftId()));

    // Review-origin subset in the window -> reuse the queue's structured per-draft derivation.
    List<DraftQuote> roQuotes = quotes.stream().filter(q -> windowIds.contains(q.getId()) && q.getSourceValidationRunId() != null).toList();
    List<DraftOrder> roOrders = orders.stream().filter(o -> windowIds.contains(o.getId()) && o.getSourceValidationRunId() != null).toList();
    List<UUID> roQuoteIds = roQuotes.stream().map(DraftQuote::getId).toList();
    List<UUID> roOrderIds = roOrders.stream().map(DraftOrder::getId).toList();
    Map<UUID, Integer> quoteLineCounts = lineCounts(draftQuoteLineRepository.countByDraftQuoteIds(tenantId, roQuoteIds));
    Map<UUID, Integer> orderLineCounts = lineCounts(draftOrderLineRepository.countByDraftOrderIds(tenantId, roOrderIds));
    Map<UUID, RemediationDerivation> derivations =
        buildRemediationDerivations(tenantId, roQuotes, roOrders, roQuoteIds, roOrderIds, quoteLineCounts, orderLineCounts);

    List<ValidationReviewDraftRecentRemediationRollupItem> items = new ArrayList<>();
    int reviewOriginDraftCount = 0;
    int draftLineCount = 0, traceable = 0, remediated = 0, totalActions = 0, corr = 0, iss = 0, appr = 0;
    Instant latest = null;
    TreeSet<String> limitationUnion = new TreeSet<>();

    for (InspectedDraft w : window) {
      RemediationDerivation d = w.sourceValidationRunId() == null ? null : derivations.get(w.draftId());
      if (d == null) {
        // Non-review-origin (or no derivable run): explicit unavailable lineage, never fabricated.
        items.add(new ValidationReviewDraftRecentRemediationRollupItem(w.kind(), w.draftId(), w.sourceValidationRunId(),
            false, 0, 0, 0, null, List.of(ValidationReviewCommandDtos.REMEDIATION_DRAFT_NOT_REVIEW_ORIGIN)));
        limitationUnion.add(ValidationReviewCommandDtos.REMEDIATION_DRAFT_NOT_REVIEW_ORIGIN);
        continue;
      }
      reviewOriginDraftCount++;
      ValidationReviewDraftRemediationSummary s = d.summary();
      ValidationReviewDraftRemediationRollup r = d.rollup();
      items.add(new ValidationReviewDraftRecentRemediationRollupItem(w.kind(), w.draftId(), w.sourceValidationRunId(),
          r.remediationLineageAvailable(), r.remediationActionCount(), r.remediatedLineCount(), r.traceableLineCount(),
          r.latestRemediationActionAt(), r.limitationCodes()));
      draftLineCount += s.draftLineCount();
      traceable += r.traceableLineCount();
      remediated += r.remediatedLineCount();
      corr += s.correctionActionCount();
      iss += s.issueResolutionActionCount();
      appr += s.approvalActionCount();
      totalActions += r.remediationActionCount();
      latest = maxInstant(latest, r.latestRemediationActionAt());
      limitationUnion.addAll(r.limitationCodes());
    }

    int lineageAvailableDraftCount = (int) items.stream().filter(ValidationReviewDraftRecentRemediationRollupItem::remediationLineageAvailable).count();
    List<ValidationReviewDraftRecentRemediationRollupItem> topLimited = items.stream()
        .filter(i -> !i.limitationCodes().isEmpty())
        .sorted(Comparator.comparing(ValidationReviewDraftRecentRemediationRollupItem::latestRemediationActionAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(i -> i.draftId().toString()))
        .limit(ValidationReviewCommandDtos.MAX_TOP_LIMITED_DRAFTS)
        .toList();

    return new ValidationReviewDraftRecentRemediationRollupResponse(
        window.size(), reviewOriginDraftCount, lineageAvailableDraftCount, window.size() - lineageAvailableDraftCount,
        draftLineCount, traceable, remediated, totalActions, corr, iss, appr, latest,
        List.copyOf(limitationUnion), topLimited, lim, "DISABLED");
  }

  private int clampRollupLimit(Integer limit) {
    if (limit == null || limit <= 0) {
      return ValidationReviewCommandDtos.DEFAULT_RECENT_ROLLUP_LIMIT;
    }
    return Math.min(limit, ValidationReviewCommandDtos.MAX_RECENT_ROLLUP_LIMIT);
  }

  /** OP-CAP-15I — both read-only remediation projections for one draft from the same structured batch data. */
  private record RemediationDerivation(ValidationReviewDraftRemediationSummary summary, ValidationReviewDraftRemediationRollup rollup) {}

  /**
   * OP-CAP-15G/15I — derive each draft's remediation summary AND compact rollup from structured, tenant-scoped
   * records only, using a fixed number of bounded batch queries (no per-row query). Every count is keyed by
   * stable ids: draft line source ids, validation issue ids and OperatorAction target ids. No free-text is
   * parsed. The OP-CAP-15I rollup adds {@code traceableLineCount} (drafted lines with a source id) and
   * {@code latestRemediationActionAt} (max createdAt across attached structured actions) from the same data.
   */
  private Map<UUID, RemediationDerivation> buildRemediationDerivations(
      UUID tenantId, List<DraftQuote> quotes, List<DraftOrder> orders, List<UUID> quoteIds, List<UUID> orderIds,
      Map<UUID, Integer> quoteLineCounts, Map<UUID, Integer> orderLineCounts) {

    // draftId -> source review run; draftId -> drafted source extracted-line-item ids; draftId -> traceable line count.
    Map<UUID, UUID> draftToRun = new HashMap<>();
    Map<UUID, Set<UUID>> draftToSourceLines = new HashMap<>();
    Map<UUID, Integer> traceableLineCounts = new HashMap<>();
    quotes.forEach(q -> draftToRun.put(q.getId(), q.getSourceValidationRunId()));
    orders.forEach(o -> draftToRun.put(o.getId(), o.getSourceValidationRunId()));
    collectSourceLines(draftToSourceLines, traceableLineCounts, quoteIds.isEmpty() ? List.of() : draftQuoteLineRepository.sourceLineIdsByDraftQuoteIds(tenantId, quoteIds));
    collectSourceLines(draftToSourceLines, traceableLineCounts, orderIds.isEmpty() ? List.of() : draftOrderLineRepository.sourceLineIdsByDraftOrderIds(tenantId, orderIds));

    Set<UUID> allSourceLines = new HashSet<>();
    draftToSourceLines.values().forEach(allSourceLines::addAll);
    Set<UUID> allRunIds = new LinkedHashSet<>();
    draftToRun.values().forEach(runId -> { if (runId != null) allRunIds.add(runId); });

    // Batch 1: line-item correction actions targeting any drafted source line (+ latest createdAt).
    Map<UUID, Integer> correctionActionsByLine = new HashMap<>();
    Map<UUID, Instant> correctionLatestByLine = new HashMap<>();
    for (OperatorAction action : batchActions(tenantId, TARGET_LINE, ACTION_LINE_CORRECTION, allSourceLines)) {
      correctionActionsByLine.merge(action.getTargetId(), 1, Integer::sum);
      mergeLatest(correctionLatestByLine, action.getTargetId(), action.getCreatedAt());
    }

    // Batch 2: issues for the page's runs -> issueId -> source line id (only line-scoped issues).
    Map<UUID, UUID> issueToLine = new HashMap<>();
    for (ValidationIssue issue : allRunIds.isEmpty() ? List.<ValidationIssue>of()
        : validationIssueRepository.findByTenantIdAndValidationRunIdInOrderByCreatedAtAsc(tenantId, allRunIds)) {
      if (issue.getExtractedLineItemId() != null) {
        issueToLine.put(issue.getId(), issue.getExtractedLineItemId());
      }
    }
    // Batch 3: issue-resolution actions targeting those issues -> issueId -> count (+ latest createdAt).
    Map<UUID, Integer> resolutionActionsByIssue = new HashMap<>();
    Map<UUID, Instant> resolutionLatestByIssue = new HashMap<>();
    for (OperatorAction action : batchActions(tenantId, TARGET_ISSUE, ACTION_ISSUE_RESOLUTION, issueToLine.keySet())) {
      resolutionActionsByIssue.merge(action.getTargetId(), 1, Integer::sum);
      mergeLatest(resolutionLatestByIssue, action.getTargetId(), action.getCreatedAt());
    }

    // Batch 4: approvals for the page's runs -> approvalId -> (runId, line id).
    Map<UUID, UUID> approvalToRun = new HashMap<>();
    Map<UUID, UUID> approvalToLine = new HashMap<>();
    for (ApprovalRequirement approval : allRunIds.isEmpty() ? List.<ApprovalRequirement>of()
        : approvalRequirementRepository.findByTenantIdAndValidationRunIdInOrderByCreatedAtAsc(tenantId, allRunIds)) {
      approvalToRun.put(approval.getId(), approval.getValidationRunId());
      approvalToLine.put(approval.getId(), approval.getExtractedLineItemId());
    }
    // Batch 5: approval-request actions targeting those approvals -> approvalId -> count (+ latest createdAt).
    Map<UUID, Integer> approvalActionsByApproval = new HashMap<>();
    Map<UUID, Instant> approvalLatestByApproval = new HashMap<>();
    for (OperatorAction action : batchActions(tenantId, TARGET_APPROVAL, ACTION_APPROVAL_REQUEST, approvalToRun.keySet())) {
      approvalActionsByApproval.merge(action.getTargetId(), 1, Integer::sum);
      mergeLatest(approvalLatestByApproval, action.getTargetId(), action.getCreatedAt());
    }

    Map<UUID, RemediationDerivation> result = new HashMap<>();
    for (UUID draftId : draftToRun.keySet()) {
      int draftLineCount = quoteLineCounts.containsKey(draftId) ? quoteLineCounts.get(draftId) : orderLineCounts.getOrDefault(draftId, 0);
      int traceableLineCount = traceableLineCounts.getOrDefault(draftId, 0);
      Set<UUID> sourceLines = draftToSourceLines.getOrDefault(draftId, Set.of());
      if (sourceLines.isEmpty()) {
        // No structured source-line lineage on this draft — only the line count is meaningful.
        ValidationReviewDraftRemediationSummary summary = new ValidationReviewDraftRemediationSummary(false, draftLineCount, 0, 0, 0, 0,
            List.of(ValidationReviewCommandDtos.REMEDIATION_LINEAGE_MISSING));
        ValidationReviewDraftRemediationRollup rollup = new ValidationReviewDraftRemediationRollup(false, 0, 0, traceableLineCount,
            List.of(ValidationReviewCommandDtos.REMEDIATION_LINEAGE_MISSING), null);
        result.put(draftId, new RemediationDerivation(summary, rollup));
        continue;
      }
      UUID runId = draftToRun.get(draftId);
      Set<UUID> remediatedLines = new HashSet<>();
      int correctionActionCount = 0;
      Instant latest = null;
      for (UUID line : sourceLines) {
        int c = correctionActionsByLine.getOrDefault(line, 0);
        if (c > 0) { correctionActionCount += c; remediatedLines.add(line); latest = maxInstant(latest, correctionLatestByLine.get(line)); }
      }
      int issueResolutionActionCount = 0;
      for (Map.Entry<UUID, Integer> entry : resolutionActionsByIssue.entrySet()) {
        UUID line = issueToLine.get(entry.getKey());
        if (line != null && sourceLines.contains(line)) {
          issueResolutionActionCount += entry.getValue();
          remediatedLines.add(line);
          latest = maxInstant(latest, resolutionLatestByIssue.get(entry.getKey()));
        }
      }
      int approvalActionCount = 0;
      for (Map.Entry<UUID, Integer> entry : approvalActionsByApproval.entrySet()) {
        UUID apprRun = approvalToRun.get(entry.getKey());
        if (apprRun == null || !apprRun.equals(runId)) continue; // only approvals from this draft's review
        UUID apprLine = approvalToLine.get(entry.getKey());
        if (apprLine == null || sourceLines.contains(apprLine)) {
          approvalActionCount += entry.getValue();
          latest = maxInstant(latest, approvalLatestByApproval.get(entry.getKey()));
        }
      }
      ValidationReviewDraftRemediationSummary summary = new ValidationReviewDraftRemediationSummary(
          true, draftLineCount, remediatedLines.size(), correctionActionCount, issueResolutionActionCount, approvalActionCount, List.of());
      ValidationReviewDraftRemediationRollup rollup = new ValidationReviewDraftRemediationRollup(
          true, correctionActionCount + issueResolutionActionCount + approvalActionCount, remediatedLines.size(),
          traceableLineCount, List.of(), latest);
      result.put(draftId, new RemediationDerivation(summary, rollup));
    }
    return result;
  }

  private List<OperatorAction> batchActions(UUID tenantId, String targetType, String actionType, Set<UUID> targetIds) {
    if (targetIds.isEmpty()) return List.of();
    return operatorActionRepository.findByTenantIdAndTargetTypeAndActionTypeAndTargetIdIn(tenantId, targetType, actionType, targetIds);
  }

  private void collectSourceLines(Map<UUID, Set<UUID>> draftToSourceLines, Map<UUID, Integer> traceableLineCounts, List<Object[]> rows) {
    for (Object[] row : rows) {
      UUID draftId = (UUID) row[0];
      UUID sourceLineId = (UUID) row[1];
      Set<UUID> set = draftToSourceLines.computeIfAbsent(draftId, k -> new HashSet<>());
      if (sourceLineId != null) {
        set.add(sourceLineId);
        traceableLineCounts.merge(draftId, 1, Integer::sum);
      }
    }
  }

  private static void mergeLatest(Map<UUID, Instant> latest, UUID key, Instant candidate) {
    if (candidate == null) return;
    latest.merge(key, candidate, (a, b) -> a.isAfter(b) ? a : b);
  }

  private static Instant maxInstant(Instant current, Instant candidate) {
    if (candidate == null) return current;
    if (current == null) return candidate;
    return current.isAfter(candidate) ? current : candidate;
  }

  private String normalizeDraftType(String draftTypeFilter) {
    if (draftTypeFilter == null || draftTypeFilter.isBlank()) {
      return null;
    }
    String type = draftTypeFilter.trim().toUpperCase(Locale.ROOT);
    if (!"QUOTE".equals(type) && !"ORDER".equals(type)) {
      throw new IllegalArgumentException("invalid_draft_type_filter");
    }
    return type;
  }

  private int clampLimit(Integer limit) {
    if (limit == null || limit <= 0) {
      return ValidationReviewCommandDtos.DEFAULT_DRAFT_QUEUE_LIMIT;
    }
    return Math.min(limit, ValidationReviewCommandDtos.MAX_DRAFT_QUEUE_LIMIT);
  }

  private Map<UUID, Integer> lineCounts(List<Object[]> grouped) {
    Map<UUID, Integer> counts = new HashMap<>();
    for (Object[] row : grouped) {
      counts.put((UUID) row[0], ((Number) row[1]).intValue());
    }
    return counts;
  }

  private boolean notePresent(String notes) {
    return notes != null && !notes.isBlank();
  }

  private String reviewPath(UUID validationRunId) {
    return validationRunId == null ? null : "/validations/" + validationRunId + "/review";
  }
}
