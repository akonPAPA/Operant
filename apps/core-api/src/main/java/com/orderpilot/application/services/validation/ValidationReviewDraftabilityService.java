package com.orderpilot.application.services.validation;

import com.orderpilot.api.dto.ValidationReviewCommandDtos;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftabilityResponse;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewLineDraftability;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewLineRemediation;
import com.orderpilot.api.dto.Stage6Dtos.BlockingReason;
import com.orderpilot.application.services.workspace.DraftCommandPreparationService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.validation.ProductMatchResult;
import com.orderpilot.domain.validation.ProductMatchResultRepository;
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import com.orderpilot.domain.validation.ValidationRun;
import com.orderpilot.domain.validation.ValidationRunRepository;
import com.orderpilot.domain.workspace.DraftOrder;
import com.orderpilot.domain.workspace.DraftOrderLine;
import com.orderpilot.domain.workspace.DraftOrderLineRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteLine;
import com.orderpilot.domain.workspace.DraftQuoteLineRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-15C — advisory per-line draftability hints for the validation review surface.
 *
 * <p>Read-only. Tells the review UI whether each validated line is currently draftable and surfaces the
 * canonical case-level readiness gate so the operator can see, before submit, what is or is not draftable
 * and why. Hints are advisory only — the OP-CAP-15A/15B draft-create endpoint re-validates and remains the
 * final authority (a line hinted draftable that has since changed still fails closed on create).
 *
 * <p>Trust boundary: this read creates no draft and no {@link ExceptionCase} (it reuses the canonical
 * {@link DraftCommandPreparationService#readiness} gate via a transient, unsaved case so there is zero
 * write side effect and zero duplication of the readiness logic). Line hints use validated/normalized
 * values only — never raw AI values as trusted business data — and are strictly tenant-scoped.
 */
@Service
public class ValidationReviewDraftabilityService {
  private static final Set<String> BLOCKING_SEVERITIES = Set.of("CRITICAL", "ERROR");

  private final ValidationRunRepository runRepository;
  private final ExtractedLineItemRepository lineRepository;
  private final ValidationIssueRepository issueRepository;
  private final ProductMatchResultRepository productMatchRepository;
  private final ExceptionCaseRepository caseRepository;
  private final DraftCommandPreparationService draftCommandPreparationService;
  private final DraftQuoteRepository draftQuoteRepository;
  private final DraftOrderRepository draftOrderRepository;
  private final DraftQuoteLineRepository draftQuoteLineRepository;
  private final DraftOrderLineRepository draftOrderLineRepository;

  public ValidationReviewDraftabilityService(
      ValidationRunRepository runRepository,
      ExtractedLineItemRepository lineRepository,
      ValidationIssueRepository issueRepository,
      ProductMatchResultRepository productMatchRepository,
      ExceptionCaseRepository caseRepository,
      DraftCommandPreparationService draftCommandPreparationService,
      DraftQuoteRepository draftQuoteRepository,
      DraftOrderRepository draftOrderRepository,
      DraftQuoteLineRepository draftQuoteLineRepository,
      DraftOrderLineRepository draftOrderLineRepository) {
    this.runRepository = runRepository;
    this.lineRepository = lineRepository;
    this.issueRepository = issueRepository;
    this.productMatchRepository = productMatchRepository;
    this.caseRepository = caseRepository;
    this.draftCommandPreparationService = draftCommandPreparationService;
    this.draftQuoteRepository = draftQuoteRepository;
    this.draftOrderRepository = draftOrderRepository;
    this.draftQuoteLineRepository = draftQuoteLineRepository;
    this.draftOrderLineRepository = draftOrderLineRepository;
  }

  @Transactional(readOnly = true)
  public ValidationReviewDraftabilityResponse draftability(UUID validationRunId) {
    UUID tenantId = TenantContext.requireTenantId();
    ValidationRun run = runRepository.findByIdAndTenantId(validationRunId, tenantId)
        .orElseThrow(() -> new NotFoundException("validation_run_not_found"));

    // Existing draft (one per source review): type, id, workspace path, and the set of already-included lines.
    Optional<DraftQuote> existingQuote = draftQuoteRepository.findFirstByTenantIdAndSourceValidationRunIdOrderByCreatedAtAsc(tenantId, validationRunId);
    Optional<DraftOrder> existingOrder = existingQuote.isPresent() ? Optional.empty()
        : draftOrderRepository.findFirstByTenantIdAndSourceValidationRunIdOrderByCreatedAtAsc(tenantId, validationRunId);
    boolean draftExists = existingQuote.isPresent() || existingOrder.isPresent();
    String existingType = existingQuote.map(q -> "QUOTE").orElse(existingOrder.isPresent() ? "ORDER" : null);
    UUID existingId = existingQuote.map(DraftQuote::getId).orElse(existingOrder.map(DraftOrder::getId).orElse(null));
    String existingPath = existingId == null ? null
        : ("QUOTE".equals(existingType) ? "/workspace/draft-quotes/" : "/workspace/draft-orders/") + existingId;
    Set<UUID> includedLineIds = includedSourceLineIds(tenantId, existingQuote.orElse(null), existingOrder.orElse(null));

    // Source case (read-only lookup — never created here).
    Optional<ExceptionCase> reviewCase = caseRepository.findFirstByTenantIdAndValidationRunIdOrderByCreatedAtDesc(tenantId, validationRunId);
    UUID sourceCaseId = reviewCase.map(ExceptionCase::getId).orElse(null);

    // Canonical case-level readiness gate (authoritative on POST). Reuse it via the persisted case when one
    // exists, else a transient unsaved case mirroring how create would key it — no write side effect.
    ExceptionCase gateCase = reviewCase.orElseGet(() -> transientCase(tenantId, run, validationRunId));
    var readiness = draftCommandPreparationService.readiness(gateCase);
    boolean caseDraftable = readiness.draftPreparationAllowed();
    List<String> caseBlockingReasons = readiness.blockingReasons().stream()
        .map(BlockingReason::issueCode)
        .filter(code -> code != null && !code.isBlank())
        .distinct()
        .toList();

    List<ExtractedLineItem> runLines = lineRepository.findByTenantIdAndExtractionResultId(tenantId, run.getExtractionResultId());
    if (runLines.isEmpty()) {
      return new ValidationReviewDraftabilityResponse(validationRunId, sourceCaseId, draftExists, existingType, existingId,
          existingPath, false, ValidationReviewCommandDtos.SEVERITY_BLOCKED,
          List.of(ValidationReviewCommandDtos.REASON_NO_DRAFTABLE_LINE_ITEMS), 0, 0, List.of(), "DISABLED");
    }

    Map<UUID, List<ValidationIssue>> openIssuesByLine = issueRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, validationRunId).stream()
        .filter(issue -> "OPEN".equals(issue.getStatus()) && issue.getExtractedLineItemId() != null)
        .collect(Collectors.groupingBy(ValidationIssue::getExtractedLineItemId));
    Map<UUID, ProductMatchResult> matchesByLine = productMatchRepository.findByTenantIdAndValidationRunId(tenantId, validationRunId).stream()
        .collect(Collectors.toMap(ProductMatchResult::getExtractedLineItemId, Function.identity(), (a, b) -> a));

    List<ValidationReviewLineDraftability> lines = new ArrayList<>();
    int draftableCount = 0;
    boolean anyWarning = false;
    for (ExtractedLineItem line : runLines) {
      ValidationReviewLineDraftability hint = lineHint(line, openIssuesByLine.getOrDefault(line.getId(), List.of()),
          matchesByLine.get(line.getId()), includedLineIds.contains(line.getId()), validationRunId, sourceCaseId);
      lines.add(hint);
      if (hint.draftable() && !hint.alreadyDrafted()) {
        draftableCount++;
      }
      if (hint.hasWarningIssue()) {
        anyWarning = true;
      }
    }

    String overallSeverity = !caseDraftable ? ValidationReviewCommandDtos.SEVERITY_BLOCKED
        : anyWarning ? ValidationReviewCommandDtos.SEVERITY_WARNING
        : ValidationReviewCommandDtos.SEVERITY_OK;

    return new ValidationReviewDraftabilityResponse(validationRunId, sourceCaseId, draftExists, existingType, existingId,
        existingPath, caseDraftable, overallSeverity, caseBlockingReasons, lines.size(), draftableCount, lines, "DISABLED");
  }

  private ValidationReviewLineDraftability lineHint(ExtractedLineItem line, List<ValidationIssue> openIssues,
      ProductMatchResult match, boolean alreadyDrafted, UUID runId, UUID caseId) {
    List<String> reasons = new ArrayList<>();
    List<ValidationReviewLineRemediation> remediations = new ArrayList<>();
    UUID lineId = line.getId();
    boolean hasBlockingIssue = openIssues.stream().anyMatch(issue -> BLOCKING_SEVERITIES.contains(issue.getSeverity()));
    boolean hasWarningIssue = openIssues.stream().anyMatch(issue -> "WARNING".equals(issue.getSeverity()));

    if (hasBlockingIssue) {
      reasons.add(ValidationReviewCommandDtos.REASON_BLOCKING_ISSUE_UNRESOLVED);
      UUID issueId = openIssues.stream().filter(issue -> BLOCKING_SEVERITIES.contains(issue.getSeverity()))
          .map(ValidationIssue::getId).findFirst().orElse(null);
      remediations.add(new ValidationReviewLineRemediation(ValidationReviewCommandDtos.REASON_BLOCKING_ISSUE_UNRESOLVED,
          ValidationReviewCommandDtos.REMEDIATION_RESOLVE_ISSUE, issueId, lineId, "Resolve the blocking validation issue"));
    }
    BigDecimal qty = line.getNormalizedQuantity();
    if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
      reasons.add(ValidationReviewCommandDtos.REASON_QUANTITY_NOT_NORMALIZED);
      remediations.add(new ValidationReviewLineRemediation(ValidationReviewCommandDtos.REASON_QUANTITY_NOT_NORMALIZED,
          ValidationReviewCommandDtos.REMEDIATION_CORRECT_LINE, null, lineId, "Correct the line quantity"));
    }
    String uom = line.getNormalizedUom();
    if (uom == null || uom.isBlank()) {
      reasons.add(ValidationReviewCommandDtos.REASON_UOM_NOT_NORMALIZED);
      remediations.add(new ValidationReviewLineRemediation(ValidationReviewCommandDtos.REASON_UOM_NOT_NORMALIZED,
          ValidationReviewCommandDtos.REMEDIATION_CORRECT_LINE, null, lineId, "Correct the line unit of measure"));
    }
    boolean matched = match != null && "MATCHED".equals(match.getStatus()) && match.getMatchedProductId() != null;
    if (!matched) {
      String matchReason = match == null ? ValidationReviewCommandDtos.REASON_SKU_NOT_VALIDATED
          : ValidationReviewCommandDtos.REASON_PRODUCT_MATCH_MISSING;
      reasons.add(matchReason);
      remediations.add(new ValidationReviewLineRemediation(matchReason,
          ValidationReviewCommandDtos.REMEDIATION_CORRECT_LINE, null, lineId, "Map the raw SKU to an existing product"));
    }

    // A line is "blocked" if it carries any hard line-local blocker (issue/normalization/match).
    boolean blocked = hasBlockingIssue
        || qty == null || qty.compareTo(BigDecimal.ZERO) <= 0
        || uom == null || uom.isBlank()
        || !matched;
    String severity = blocked ? ValidationReviewCommandDtos.SEVERITY_BLOCKED
        : hasWarningIssue ? ValidationReviewCommandDtos.SEVERITY_WARNING
        : ValidationReviewCommandDtos.SEVERITY_OK;
    if (severity.equals(ValidationReviewCommandDtos.SEVERITY_WARNING)) {
      reasons.add(ValidationReviewCommandDtos.REASON_WARNING_ISSUE_PRESENT);
      UUID warnIssueId = openIssues.stream().filter(issue -> "WARNING".equals(issue.getSeverity()))
          .map(ValidationIssue::getId).findFirst().orElse(null);
      remediations.add(new ValidationReviewLineRemediation(ValidationReviewCommandDtos.REASON_WARNING_ISSUE_PRESENT,
          ValidationReviewCommandDtos.REMEDIATION_VIEW_ISSUE, warnIssueId, lineId, "Review the warning validation issue"));
    }
    if (reasons.isEmpty()) {
      reasons.add(ValidationReviewCommandDtos.REASON_LINE_READY);
    }

    // Already-drafted lines are not re-correctable from this surface: offer no remediation action.
    if (alreadyDrafted) {
      reasons.add(ValidationReviewCommandDtos.REASON_LINE_ALREADY_INCLUDED);
      remediations.clear();
    }

    String normalizedSku = matched && match != null ? match.getRawSku() : line.getRawSku();
    return new ValidationReviewLineDraftability(lineId, line.getLineNumber(), !blocked, severity,
        List.copyOf(reasons), normalizedSku, qty, uom, hasBlockingIssue, hasWarningIssue, alreadyDrafted, runId, caseId,
        List.copyOf(remediations));
  }

  // The set of validated source line ids already included in the existing draft (links draft lines back
  // to their source via source_extracted_line_item_id).
  private Set<UUID> includedSourceLineIds(UUID tenantId, DraftQuote quote, DraftOrder order) {
    Set<UUID> ids = new LinkedHashSet<>();
    if (quote != null) {
      for (DraftQuoteLine line : draftQuoteLineRepository.findByTenantIdAndDraftQuoteId(tenantId, quote.getId())) {
        ids.add(line.getSourceExtractedLineItemId());
      }
    }
    if (order != null) {
      for (DraftOrderLine line : draftOrderLineRepository.findByTenantIdAndDraftOrderId(tenantId, order.getId())) {
        ids.add(line.getSourceExtractedLineItemId());
      }
    }
    ids.remove(null);
    return ids;
  }

  // Transient (unsaved) case mirroring how create keys readiness for this run. Status "OPEN" yields the
  // same readiness outcome as the persisted case create would build (approval gating derives from
  // open issues/approvals, not from this status token).
  private ExceptionCase transientCase(UUID tenantId, ValidationRun run, UUID validationRunId) {
    return new ExceptionCase(tenantId, "TRANSIENT", "VALIDATION_RUN", validationRunId, run.getExtractionResultId(),
        validationRunId, null, "Draftability probe", "OPEN", "LOW", "INFO", "read-only draftability probe", Instant.now());
  }
}
