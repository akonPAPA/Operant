package com.orderpilot.application.services.validation;

import com.orderpilot.api.dto.Stage6Dtos.DraftPreparationResult;
import com.orderpilot.api.dto.ValidationReviewCommandDtos;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftResult;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftStatus;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import com.orderpilot.domain.validation.ValidationRun;
import com.orderpilot.domain.validation.ValidationRunRepository;
import com.orderpilot.domain.workspace.DraftOrder;
import com.orderpilot.domain.workspace.DraftOrderLineRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteLineRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import com.orderpilot.application.services.workspace.DraftCommandPreparationService;
import com.orderpilot.application.services.workspace.ExceptionCaseService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-15A/15B — Validation Review → Draft Quote / Draft Order bridge.
 *
 * <p>Lets an authorized operator create exactly one internal draft (quote or order) from a tenant-scoped
 * validation run review, optionally from a selected subset of validated lines and with a bounded operator
 * note, and lets the review surface see whether a draft already exists. This is a thin bridge over the
 * existing canonical {@link DraftCommandPreparationService}: it maps the validation-run review surface
 * (OP-CAP-14A/B/C/D) to the existing {@link ExceptionCase}-keyed draft preparation, reusing its readiness
 * gate (blocking issues fail closed), {@code (tenant, sourceExceptionCaseId)} idempotency,
 * corrected/validated line values, source traceability and audit. No new draft model, no migration.
 *
 * <p>Trust boundary: creates an internal draft only. It creates no final/approved order, performs no
 * ERP/1C/accounting/warehouse/connector write, and never lets AI output create business data directly —
 * the draft is built from deterministic, operator-reviewed validation artifacts.
 */
@Service
public class ValidationReviewDraftCommandService {
  private final ValidationRunRepository runRepository;
  private final ExceptionCaseRepository caseRepository;
  private final ExceptionCaseService exceptionCaseService;
  private final DraftCommandPreparationService draftCommandPreparationService;
  private final ValidationIssueRepository issueRepository;
  private final ApprovalRequirementService approvalRequirementService;
  private final ExtractedLineItemRepository lineRepository;
  private final DraftQuoteRepository draftQuoteRepository;
  private final DraftOrderRepository draftOrderRepository;
  private final DraftQuoteLineRepository draftQuoteLineRepository;
  private final DraftOrderLineRepository draftOrderLineRepository;

  public ValidationReviewDraftCommandService(
      ValidationRunRepository runRepository,
      ExceptionCaseRepository caseRepository,
      ExceptionCaseService exceptionCaseService,
      DraftCommandPreparationService draftCommandPreparationService,
      ValidationIssueRepository issueRepository,
      ApprovalRequirementService approvalRequirementService,
      ExtractedLineItemRepository lineRepository,
      DraftQuoteRepository draftQuoteRepository,
      DraftOrderRepository draftOrderRepository,
      DraftQuoteLineRepository draftQuoteLineRepository,
      DraftOrderLineRepository draftOrderLineRepository) {
    this.runRepository = runRepository;
    this.caseRepository = caseRepository;
    this.exceptionCaseService = exceptionCaseService;
    this.draftCommandPreparationService = draftCommandPreparationService;
    this.issueRepository = issueRepository;
    this.approvalRequirementService = approvalRequirementService;
    this.lineRepository = lineRepository;
    this.draftQuoteRepository = draftQuoteRepository;
    this.draftOrderRepository = draftOrderRepository;
    this.draftQuoteLineRepository = draftQuoteLineRepository;
    this.draftOrderLineRepository = draftOrderLineRepository;
  }

  // OP-CAP-15A signatures (all eligible lines, no note) — preserved.
  @Transactional
  public ValidationReviewDraftResult createDraftQuote(UUID validationRunId, UUID actorUserId) {
    return create(validationRunId, "QUOTE", actorUserId, null, null);
  }

  @Transactional
  public ValidationReviewDraftResult createDraftOrder(UUID validationRunId, UUID actorUserId) {
    return create(validationRunId, "ORDER", actorUserId, null, null);
  }

  // OP-CAP-15B signatures — optional selected lines + operator note.
  @Transactional
  public ValidationReviewDraftResult createDraftQuote(UUID validationRunId, UUID actorUserId, List<UUID> selectedLineIds, String operatorNote) {
    return create(validationRunId, "QUOTE", actorUserId, selectedLineIds, operatorNote);
  }

  @Transactional
  public ValidationReviewDraftResult createDraftOrder(UUID validationRunId, UUID actorUserId, List<UUID> selectedLineIds, String operatorNote) {
    return create(validationRunId, "ORDER", actorUserId, selectedLineIds, operatorNote);
  }

  /** OP-CAP-15B — read-only draft visibility for the validation review surface. */
  @Transactional(readOnly = true)
  public ValidationReviewDraftStatus draftStatus(UUID validationRunId) {
    UUID tenantId = TenantContext.requireTenantId();
    runRepository.findByIdAndTenantId(validationRunId, tenantId)
        .orElseThrow(() -> new NotFoundException("validation_run_not_found"));

    Optional<DraftQuote> quote = draftQuoteRepository.findFirstByTenantIdAndSourceValidationRunIdOrderByCreatedAtAsc(tenantId, validationRunId);
    if (quote.isPresent()) {
      DraftQuote q = quote.get();
      int lineCount = draftQuoteLineRepository.findByTenantIdAndDraftQuoteId(tenantId, q.getId()).size();
      return new ValidationReviewDraftStatus(true, "QUOTE", q.getId(), "/workspace/draft-quotes/" + q.getId(),
          validationRunId, q.getSourceExceptionCaseId(), lineCount, q.getCreatedAt(), "DISABLED");
    }
    Optional<DraftOrder> order = draftOrderRepository.findFirstByTenantIdAndSourceValidationRunIdOrderByCreatedAtAsc(tenantId, validationRunId);
    if (order.isPresent()) {
      DraftOrder o = order.get();
      int lineCount = draftOrderLineRepository.findByTenantIdAndDraftOrderId(tenantId, o.getId()).size();
      return new ValidationReviewDraftStatus(true, "ORDER", o.getId(), "/workspace/draft-orders/" + o.getId(),
          validationRunId, o.getSourceExceptionCaseId(), lineCount, o.getCreatedAt(), "DISABLED");
    }
    return new ValidationReviewDraftStatus(false, null, null, null, validationRunId, null, 0, null, "DISABLED");
  }

  private ValidationReviewDraftResult create(UUID validationRunId, String draftType, UUID actorUserId, List<UUID> selectedLineIds, String operatorNote) {
    UUID tenantId = TenantContext.requireTenantId();
    ValidationRun run = runRepository.findByIdAndTenantId(validationRunId, tenantId)
        .orElseThrow(() -> new NotFoundException("validation_run_not_found"));

    String note = boundedNote(operatorNote);

    // A draft must have at least one validated line to build from.
    List<ExtractedLineItem> runLines = lineRepository.findByTenantIdAndExtractionResultId(tenantId, run.getExtractionResultId());
    if (runLines.isEmpty()) {
      throw new IllegalArgumentException("no_valid_line_items");
    }

    // Validate the optional selected-line subset against this run/tenant (never silently ignore invalid ids).
    Set<UUID> selectedSet = validateSelection(selectedLineIds, runLines);

    // Map the validation-run review to its (find-or-create) ExceptionCase so the existing readiness gate,
    // idempotency key and audit all apply. find-first keeps idempotent replays on the same source case.
    ExceptionCase reviewCase = caseRepository.findFirstByTenantIdAndValidationRunIdOrderByCreatedAtDesc(tenantId, validationRunId)
        .orElseGet(() -> exceptionCaseService.createFromValidation(validationRunId));

    DraftPreparationResult prep = draftCommandPreparationService.prepareDraft(reviewCase.getId(), actorUserId, draftType, selectedSet, note);
    return toResult(tenantId, validationRunId, prep);
  }

  // Returns null when selection is omitted (all lines), else a validated, de-duplicated set of run line ids.
  private Set<UUID> validateSelection(List<UUID> selectedLineIds, List<ExtractedLineItem> runLines) {
    if (selectedLineIds == null) {
      return null;
    }
    if (selectedLineIds.isEmpty()) {
      throw new IllegalArgumentException("selected_lines_empty");
    }
    Set<UUID> runLineIds = new LinkedHashSet<>();
    runLines.forEach(line -> runLineIds.add(line.getId()));
    Set<UUID> selected = new LinkedHashSet<>();
    for (UUID id : selectedLineIds) {
      if (id == null || !runLineIds.contains(id)) {
        // Includes ids from another run/tenant (never present in this run's tenant-scoped line set).
        throw new IllegalArgumentException("selected_line_not_found");
      }
      selected.add(id);
    }
    if (selected.isEmpty()) {
      throw new IllegalArgumentException("no_draftable_line_items");
    }
    return selected;
  }

  private String boundedNote(String operatorNote) {
    if (operatorNote == null) {
      return null;
    }
    String trimmed = operatorNote.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > ValidationReviewCommandDtos.MAX_OPERATOR_NOTE) {
      throw new IllegalArgumentException("operator_note_too_long");
    }
    return trimmed;
  }

  private ValidationReviewDraftResult toResult(UUID tenantId, UUID validationRunId, DraftPreparationResult prep) {
    int lineCount = "QUOTE".equals(prep.draftType())
        ? draftQuoteLineRepository.findByTenantIdAndDraftQuoteId(tenantId, prep.draftId()).size()
        : draftOrderLineRepository.findByTenantIdAndDraftOrderId(tenantId, prep.draftId()).size();

    List<ValidationIssue> openIssues = issueRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, validationRunId).stream()
        .filter(issue -> "OPEN".equals(issue.getStatus()))
        .toList();
    int blocking = (int) openIssues.stream()
        .filter(issue -> "CRITICAL".equals(issue.getSeverity()) || "ERROR".equals(issue.getSeverity()))
        .count();
    int warning = (int) openIssues.stream()
        .filter(issue -> "WARNING".equals(issue.getSeverity()))
        .count();
    boolean approvalRequired = approvalRequirementService.list(validationRunId).stream()
        .anyMatch(approval -> "OPEN".equals(approval.getStatus()));

    String nextRoute = "QUOTE".equals(prep.draftType())
        ? "/workspace/draft-quotes/" + prep.draftId()
        : "/workspace/draft-orders/" + prep.draftId();

    return new ValidationReviewDraftResult(
        prep.draftId(), prep.draftType(), prep.status(), validationRunId, lineCount, blocking, warning,
        approvalRequired, prep.created(), prep.alreadyExisted(), "DISABLED", prep.nextAction(), nextRoute);
  }
}
