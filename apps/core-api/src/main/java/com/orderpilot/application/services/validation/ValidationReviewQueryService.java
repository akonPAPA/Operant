package com.orderpilot.application.services.validation;

import com.orderpilot.api.dto.ValidationReviewDtos.AllowedReviewAction;
import com.orderpilot.api.dto.ValidationReviewDtos.AuditTimelineItem;
import com.orderpilot.api.dto.ValidationReviewDtos.ExtractedFieldReviewItem;
import com.orderpilot.api.dto.ValidationReviewDtos.ExtractedLineItemReviewItem;
import com.orderpilot.api.dto.ValidationReviewDtos.ExtractionReviewSummary;
import com.orderpilot.api.dto.ValidationReviewDtos.SourceEvidenceReviewItem;
import com.orderpilot.api.dto.ValidationReviewDtos.ValidationIssueReviewItem;
import com.orderpilot.api.dto.ValidationReviewDtos.ValidationReviewDetailResponse;
import com.orderpilot.api.dto.ValidationReviewDtos.ValidationRunReviewSummary;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.extraction.ExtractedField;
import com.orderpilot.domain.extraction.ExtractedFieldRepository;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.SourceEvidence;
import com.orderpilot.domain.extraction.SourceEvidenceRepository;
import com.orderpilot.domain.validation.ApprovalRequirement;
import com.orderpilot.domain.validation.ProductMatchResult;
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationRun;
import com.orderpilot.domain.validation.ValidationRunRepository;
import com.orderpilot.domain.validation.ValidationRoutingRecommendation;
import com.orderpilot.api.dto.ValidationReviewDtos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-14A — read-only operator review composition.
 *
 * <p>Loads tenant-scoped, already-persisted deterministic validation artifacts and composes the
 * bounded {@link ValidationReviewDetailResponse}. It performs no mutation, triggers no handoff /
 * validation / draft / connector action, and never returns raw AI advisory payload, full document
 * bodies, prompt text or secrets. A missing or foreign-tenant resource fails closed as a bounded
 * {@link NotFoundException} (404), consistent with project conventions.
 */
@Service
public class ValidationReviewQueryService {
  private final ValidationRunRepository runRepository;
  private final ExtractionResultRepository extractionResultRepository;
  private final ExtractedFieldRepository fieldRepository;
  private final ExtractedLineItemRepository lineRepository;
  private final ValidationIssueService issueService;
  private final ApprovalRequirementService approvalRequirementService;
  private final ProductMatchingService productMatchingService;
  private final SourceEvidenceRepository sourceEvidenceRepository;
  private final AuditEventRepository auditEventRepository;
  private final JsonSupport jsonSupport;

  public ValidationReviewQueryService(
      ValidationRunRepository runRepository,
      ExtractionResultRepository extractionResultRepository,
      ExtractedFieldRepository fieldRepository,
      ExtractedLineItemRepository lineRepository,
      ValidationIssueService issueService,
      ApprovalRequirementService approvalRequirementService,
      ProductMatchingService productMatchingService,
      SourceEvidenceRepository sourceEvidenceRepository,
      AuditEventRepository auditEventRepository,
      JsonSupport jsonSupport) {
    this.runRepository = runRepository;
    this.extractionResultRepository = extractionResultRepository;
    this.fieldRepository = fieldRepository;
    this.lineRepository = lineRepository;
    this.issueService = issueService;
    this.approvalRequirementService = approvalRequirementService;
    this.productMatchingService = productMatchingService;
    this.sourceEvidenceRepository = sourceEvidenceRepository;
    this.auditEventRepository = auditEventRepository;
    this.jsonSupport = jsonSupport;
  }

  /** Review detail for a tenant-scoped validation run. */
  @Transactional(readOnly = true)
  public ValidationReviewDetailResponse reviewByValidationRun(UUID validationRunId) {
    UUID tenantId = TenantContext.requireTenantId();
    ValidationRun run = runRepository.findByIdAndTenantId(validationRunId, tenantId)
        .orElseThrow(() -> new NotFoundException("validation_run_not_found"));
    return compose(tenantId, run);
  }

  /** Review detail for the latest validation run of a tenant-scoped extraction result. */
  @Transactional(readOnly = true)
  public ValidationReviewDetailResponse reviewByExtractionResult(UUID extractionResultId) {
    UUID tenantId = TenantContext.requireTenantId();
    // Confirm the extraction exists and belongs to the tenant before exposing any run.
    extractionResultRepository.findByIdAndTenantId(extractionResultId, tenantId)
        .orElseThrow(() -> new NotFoundException("extraction_result_not_found"));
    ValidationRun run = runRepository
        .findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, extractionResultId).stream()
        .findFirst()
        .orElseThrow(() -> new NotFoundException("validation_run_not_found"));
    return compose(tenantId, run);
  }

  private ValidationReviewDetailResponse compose(UUID tenantId, ValidationRun run) {
    ExtractionResult extraction = extractionResultRepository
        .findByIdAndTenantId(run.getExtractionResultId(), tenantId)
        .orElseThrow(() -> new NotFoundException("extraction_result_not_found"));

    List<ExtractedField> fields = fieldRepository.findByTenantIdAndExtractionResultId(tenantId, extraction.getId());
    List<ExtractedLineItem> lines = lineRepository.findByTenantIdAndExtractionResultId(tenantId, extraction.getId()).stream()
        .sorted(Comparator.comparingInt(ExtractedLineItem::getLineNumber))
        .toList();
    List<ValidationIssue> issues = issueService.list(run.getId());
    List<ApprovalRequirement> approvals = approvalRequirementService.list(run.getId());
    Map<UUID, ProductMatchResult> productByLine = productMatchingService.list(run.getId()).stream()
        .collect(Collectors.toMap(ProductMatchResult::getExtractedLineItemId, Function.identity(), (first, ignored) -> first));

    // Issue references grouped by their field / line target (single pass, no per-row query).
    Map<UUID, List<UUID>> issueIdsByField = issues.stream()
        .filter(i -> i.getExtractedFieldId() != null)
        .collect(Collectors.groupingBy(ValidationIssue::getExtractedFieldId,
            Collectors.mapping(ValidationIssue::getId, Collectors.toList())));
    Map<UUID, List<UUID>> issueIdsByLine = issues.stream()
        .filter(i -> i.getExtractedLineItemId() != null)
        .collect(Collectors.groupingBy(ValidationIssue::getExtractedLineItemId,
            Collectors.mapping(ValidationIssue::getId, Collectors.toList())));
    Map<UUID, Integer> lineNumberById = lines.stream()
        .collect(Collectors.toMap(ExtractedLineItem::getId, ExtractedLineItem::getLineNumber, (first, ignored) -> first));

    List<ExtractedFieldReviewItem> fieldItems = fields.stream()
        .map(f -> new ExtractedFieldReviewItem(
            f.getId(), f.getFieldName(), f.getRawValue(), f.getNormalizedValue(), f.getValueType(),
            f.getConfidence(), f.getValidationStatus(), f.getSourceEvidenceId(),
            issueIdsByField.getOrDefault(f.getId(), List.of())))
        .toList();

    List<ExtractedLineItemReviewItem> lineItems = lines.stream()
        .map(l -> {
          ProductMatchResult match = productByLine.get(l.getId());
          return new ExtractedLineItemReviewItem(
              l.getId(), l.getLineNumber(), l.getRawSku(),
              match == null ? null : match.getMatchedProductId(),
              match == null ? null : match.getStatus(),
              l.getRawDescription(), l.getNormalizedQuantity(), l.getNormalizedUom(),
              l.getConfidence(), l.getValidationStatus(), l.getSourceEvidenceId(),
              issueIdsByLine.getOrDefault(l.getId(), List.of()));
        })
        .toList();

    List<ValidationIssueReviewItem> issueItems = issues.stream()
        .map(i -> toIssueItem(i, lineNumberById))
        .toList();

    List<SourceEvidenceReviewItem> evidenceItems = evidenceItems(tenantId, extraction, fields, lines);
    List<AuditTimelineItem> auditTimeline = auditTimeline(tenantId, run.getId());
    List<AllowedReviewAction> allowedActions = allowedActions(issues, approvals);

    int blocking = (int) issues.stream().filter(ValidationReviewQueryService::isBlocking).count();
    int warningReview = (int) issues.stream().filter(i -> "WARNING".equals(i.getSeverity())).count();

    ExtractionReviewSummary extractionSummary = new ExtractionReviewSummary(
        extraction.getId(), extraction.getSourceType(), extraction.getSourceId(), extraction.getDetectedIntent(),
        extraction.getDocumentType(), workerStatus(extraction), extraction.getValidationStatus(),
        extraction.getOverallConfidence(), null, true);

    ValidationRunReviewSummary runSummary = new ValidationRunReviewSummary(
        run.getId(), run.getStatus(), run.getOverallStatus(), routing(issues, approvals).name(),
        blocking, warningReview, approvals.size(), run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt());

    return new ValidationReviewDetailResponse(
        extractionSummary, runSummary, fieldItems, lineItems, issueItems, evidenceItems, auditTimeline, allowedActions, true);
  }

  private ValidationIssueReviewItem toIssueItem(ValidationIssue issue, Map<UUID, Integer> lineNumberById) {
    String targetType;
    UUID targetId;
    Integer targetLineNumber = null;
    if (issue.getExtractedFieldId() != null) {
      targetType = "FIELD";
      targetId = issue.getExtractedFieldId();
    } else if (issue.getExtractedLineItemId() != null) {
      targetType = "LINE_ITEM";
      targetId = issue.getExtractedLineItemId();
      targetLineNumber = lineNumberById.get(issue.getExtractedLineItemId());
    } else if (issue.getExtractionResultId() != null) {
      targetType = "EXTRACTION";
      targetId = issue.getExtractionResultId();
    } else {
      targetType = "VALIDATION_RUN";
      targetId = issue.getValidationRunId();
    }
    return new ValidationIssueReviewItem(
        issue.getId(), issue.getSeverity(), issue.getIssueType(), issue.getMessage(),
        targetType, targetId, targetLineNumber, isBlocking(issue), issue.getStatus());
  }

  private List<SourceEvidenceReviewItem> evidenceItems(
      UUID tenantId, ExtractionResult extraction, List<ExtractedField> fields, List<ExtractedLineItem> lines) {
    Set<UUID> referenced = new LinkedHashSet<>();
    fields.forEach(f -> { if (f.getSourceEvidenceId() != null) referenced.add(f.getSourceEvidenceId()); });
    lines.forEach(l -> { if (l.getSourceEvidenceId() != null) referenced.add(l.getSourceEvidenceId()); });
    if (referenced.isEmpty()) return List.of();
    // One tenant-scoped query for the run's evidence, then filter to referenced ids (no N+1, no foreign read).
    Map<UUID, SourceEvidence> evidenceById = sourceEvidenceRepository
        .findByTenantIdAndExtractionRunId(tenantId, extraction.getExtractionRunId()).stream()
        .collect(Collectors.toMap(SourceEvidence::getId, Function.identity(), (first, ignored) -> first));
    List<SourceEvidenceReviewItem> items = new ArrayList<>();
    for (UUID id : referenced) {
      SourceEvidence evidence = evidenceById.get(id);
      if (evidence == null) continue;
      items.add(new SourceEvidenceReviewItem(
          evidence.getId(), evidence.getEvidenceType(), evidence.getPageNumber(),
          evidence.getStartOffset(), evidence.getEndOffset(), boundedSnippet(evidence.getSnippet())));
    }
    return items;
  }

  private List<AuditTimelineItem> auditTimeline(UUID tenantId, UUID validationRunId) {
    return auditEventRepository
        .findByTenantIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(tenantId, "ValidationRun", validationRunId.toString()).stream()
        .limit(ValidationReviewDtos.MAX_AUDIT_ROWS)
        .map(this::toAuditItem)
        .toList();
  }

  private AuditTimelineItem toAuditItem(AuditEvent event) {
    // Bounded metadata only — action/actor/when. Raw metadata JSON is intentionally not exposed.
    return new AuditTimelineItem(event.getAction(), event.getEntityType(), event.getEntityId(), event.getOccurredAt());
  }

  private List<AllowedReviewAction> allowedActions(List<ValidationIssue> issues, List<ApprovalRequirement> approvals) {
    ValidationRoutingRecommendation routing = routing(issues, approvals);
    boolean hasLineWork = issues.stream().anyMatch(i -> i.getExtractedLineItemId() != null && isBlocking(i))
        || routing != ValidationRoutingRecommendation.AUTO_READY_DRAFT_ALLOWED;
    return List.of(
        new AllowedReviewAction(ValidationReviewDtos.ACTION_REVIEW_FIELDS, true, "VALIDATION_READ"),
        new AllowedReviewAction(ValidationReviewDtos.ACTION_FIX_LINE_ITEM, hasLineWork, "REVIEW_ACTION"),
        new AllowedReviewAction(ValidationReviewDtos.ACTION_APPROVE_SUBSTITUTE, !approvals.isEmpty(), "REVIEW_ACTION"),
        new AllowedReviewAction(ValidationReviewDtos.ACTION_RERUN_VALIDATION, true, "VALIDATION_RUN"),
        // Declarative hint only — draft creation is not part of this read contract.
        new AllowedReviewAction(ValidationReviewDtos.ACTION_CREATE_DRAFT_QUOTE, false, null));
  }

  private String workerStatus(ExtractionResult extraction) {
    try {
      Map<String, Object> json = jsonSupport.parseObject(extraction.getResultJson());
      Object status = json.get("workerStatus");
      return status == null ? null : status.toString();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static boolean isBlocking(ValidationIssue issue) {
    return "CRITICAL".equals(issue.getSeverity()) || "ERROR".equals(issue.getSeverity());
  }

  // Mirrors ExtractionValidationService routing semantics (CRITICAL/ERROR → blocked; WARNING or
  // any approval requirement → operator review; else auto-ready) for a read-only routing hint.
  private static ValidationRoutingRecommendation routing(List<ValidationIssue> issues, List<ApprovalRequirement> approvals) {
    boolean blocked = issues.stream().anyMatch(ValidationReviewQueryService::isBlocking);
    if (blocked) return ValidationRoutingRecommendation.BLOCKED_UNTIL_FIXED;
    if (!approvals.isEmpty() || issues.stream().anyMatch(i -> "WARNING".equals(i.getSeverity()))) {
      return ValidationRoutingRecommendation.NEEDS_OPERATOR_REVIEW;
    }
    return ValidationRoutingRecommendation.AUTO_READY_DRAFT_ALLOWED;
  }

  private static String boundedSnippet(String snippet) {
    if (snippet == null) return null;
    return snippet.length() <= ValidationReviewDtos.MAX_SNIPPET_LENGTH
        ? snippet
        : snippet.substring(0, ValidationReviewDtos.MAX_SNIPPET_LENGTH);
  }
}
