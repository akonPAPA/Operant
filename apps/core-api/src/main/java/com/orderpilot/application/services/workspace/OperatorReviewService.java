package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage6Dtos.*;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.*;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.validation.*;
import com.orderpilot.domain.workspace.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorReviewService {
  private static final List<String> GROUP_ORDER = List.of("CUSTOMER", "DOCUMENT", "LINE_PRODUCT", "PRICING", "INVENTORY", "SUBSTITUTION_COMPATIBILITY", "POLICY_APPROVAL");

  private final ExceptionCaseRepository caseRepository;
  private final ExceptionCaseIssueRepository caseIssueRepository;
  private final ExceptionCaseService exceptionCaseService;
  private final SuggestedFixService suggestedFixService;
  private final SuggestedFixRepository suggestedFixRepository;
  private final WorkspaceNoteService noteService;
  private final WorkspaceNoteRepository noteRepository;
  private final OperatorActionService actionService;
  private final OperatorActionRepository actionRepository;
  private final ValidationRunRepository validationRunRepository;
  private final ValidationIssueRepository validationIssueRepository;
  private final ApprovalRequirementRepository approvalRequirementRepository;
  private final SubstituteCandidateRepository substituteCandidateRepository;
  private final ProductMatchResultRepository productMatchResultRepository;
  private final ProductRepository productRepository;
  private final ExtractionResultRepository extractionResultRepository;
  private final ExtractedFieldRepository fieldRepository;
  private final ExtractedLineItemRepository lineRepository;
  private final SourceEvidenceRepository evidenceRepository;
  private final DraftCommandPreparationService draftCommandPreparationService;
  private final JsonSupport jsonSupport;
  private final Clock clock;

  public OperatorReviewService(ExceptionCaseRepository caseRepository, ExceptionCaseIssueRepository caseIssueRepository, ExceptionCaseService exceptionCaseService, SuggestedFixService suggestedFixService, SuggestedFixRepository suggestedFixRepository, WorkspaceNoteService noteService, WorkspaceNoteRepository noteRepository, OperatorActionService actionService, OperatorActionRepository actionRepository, ValidationRunRepository validationRunRepository, ValidationIssueRepository validationIssueRepository, ApprovalRequirementRepository approvalRequirementRepository, SubstituteCandidateRepository substituteCandidateRepository, ProductMatchResultRepository productMatchResultRepository, ProductRepository productRepository, ExtractionResultRepository extractionResultRepository, ExtractedFieldRepository fieldRepository, ExtractedLineItemRepository lineRepository, SourceEvidenceRepository evidenceRepository, DraftCommandPreparationService draftCommandPreparationService, JsonSupport jsonSupport, Clock clock) {
    this.caseRepository = caseRepository;
    this.caseIssueRepository = caseIssueRepository;
    this.exceptionCaseService = exceptionCaseService;
    this.suggestedFixService = suggestedFixService;
    this.suggestedFixRepository = suggestedFixRepository;
    this.noteService = noteService;
    this.noteRepository = noteRepository;
    this.actionService = actionService;
    this.actionRepository = actionRepository;
    this.validationRunRepository = validationRunRepository;
    this.validationIssueRepository = validationIssueRepository;
    this.approvalRequirementRepository = approvalRequirementRepository;
    this.substituteCandidateRepository = substituteCandidateRepository;
    this.productMatchResultRepository = productMatchResultRepository;
    this.productRepository = productRepository;
    this.extractionResultRepository = extractionResultRepository;
    this.fieldRepository = fieldRepository;
    this.lineRepository = lineRepository;
    this.evidenceRepository = evidenceRepository;
    this.draftCommandPreparationService = draftCommandPreparationService;
    this.jsonSupport = jsonSupport;
    this.clock = clock;
  }

  @Transactional
  public ReviewCaseDetail createForValidationRun(UUID validationRunId) {
    UUID tenantId = TenantContext.requireTenantId();
    ExceptionCase reviewCase = caseRepository.findFirstByTenantIdAndValidationRunIdOrderByCreatedAtDesc(tenantId, validationRunId)
        .orElseGet(() -> exceptionCaseService.createFromValidation(validationRunId));
    String status = reviewRequired(tenantId, validationRunId) ? "REVIEW_REQUIRED" : "REVIEW_NOT_REQUIRED";
    reviewCase.setStatus(status, clock.instant());
    reviewCase = caseRepository.save(reviewCase);
    if (suggestedFixRepository.findByTenantIdAndValidationRunId(tenantId, validationRunId).isEmpty()) {
      suggestedFixService.generate(validationRunId);
    }
    actionService.record(null, "REVIEW_CASE", reviewCase.getId(), "REVIEW_CASE_CREATED", "Review case prepared from validation run", "{\"validationRunId\":\"" + validationRunId + "\",\"status\":\"" + status + "\"}");
    return detail(reviewCase.getId());
  }

  @Transactional(readOnly = true)
  public List<ReviewCaseSummary> list() {
    return caseRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId()).stream().map(this::summary).toList();
  }

  @Transactional(readOnly = true)
  public ReviewCaseDetail detail(UUID id) {
    UUID tenantId = TenantContext.requireTenantId();
    ExceptionCase reviewCase = caseRepository.findByIdAndTenantId(id, tenantId).orElseThrow();
    if (reviewCase.getValidationRunId() == null || reviewCase.getExtractionResultId() == null) {
      throw new IllegalArgumentException("Review case is not validation-backed. Use the bot operator handoff view for sourceType=" + reviewCase.getSourceType());
    }
    ValidationRun run = validationRunRepository.findByIdAndTenantId(reviewCase.getValidationRunId(), tenantId).orElseThrow();
    ExtractionResult extraction = extractionResultRepository.findByIdAndTenantId(run.getExtractionResultId(), tenantId).orElseThrow();
    Map<UUID, SourceEvidence> evidence = evidenceRepository.findByTenantIdAndExtractionRunId(tenantId, extraction.getExtractionRunId()).stream().collect(Collectors.toMap(SourceEvidence::getId, Function.identity()));
    List<ValidationIssue> issues = validationIssueRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, run.getId());
    List<ApprovalRequirement> approvals = approvalRequirementRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, run.getId());
    List<SuggestedFix> fixes = suggestedFixRepository.findByTenantIdAndValidationRunId(tenantId, run.getId());
    List<SubstituteCandidate> substitutes = substituteCandidateRepository.findByTenantIdAndValidationRunIdOrderByRankScoreDesc(tenantId, run.getId());
    ReviewReadiness readiness = draftCommandPreparationService.readiness(reviewCase);
    List<BlockingReason> blockingReasons = readiness.blockingReasons();
    List<OperatorActionReview> timeline = actionRepository.findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(tenantId, "REVIEW_CASE", reviewCase.getId()).stream().map(a -> new OperatorActionReview(a.getId(), a.getActionType(), a.getMessage(), a.getCreatedAt())).toList();
    List<ApprovalRequirementReview> approvalReviews = approvals.stream().map(a -> new ApprovalRequirementReview(a.getId(), a.getExtractedLineItemId(), a.getRequirementType(), a.getSeverity(), a.getStatus(), a.getReason(), a.getCreatedAt())).toList();
    return new ReviewCaseDetail(
        summary(reviewCase),
        new ExtractionSummary(extraction.getId(), extraction.getSourceType(), extraction.getSourceId(), extraction.getDetectedIntent(), extraction.getDocumentType(), extraction.getOverallConfidence(), extraction.getValidationStatus()),
        new ValidationSummary(run.getId(), run.getStatus(), run.getOverallStatus(), run.getOverallConfidence(), riskLevel(issues, approvals), run.getStartedAt(), run.getFinishedAt()),
        issueGroups(issues),
        issueStatuses(issues, approvals, blockingReasons),
        approvalReviews,
        approvalReviews.stream().filter(a -> "OPEN".equals(a.status())).toList(),
        approvalReviews.stream().filter(a -> "REJECTED".equals(a.status())).toList(),
        approvalReviews.stream().filter(a -> List.of("APPROVED", "CORRECTED", "OVERRIDDEN", "ACKNOWLEDGED").contains(a.status())).toList(),
        fixes.stream().map(f -> new SuggestedActionReview(f.getId(), f.getValidationIssueId(), f.getExtractedLineItemId(), f.getFixType(), f.getStatus(), f.getConfidence(), f.getReason())).toList(),
        productCandidates(tenantId, run.getId()),
        substitutes.stream().map(s -> substituteReview(tenantId, s)).toList(),
        fieldRepository.findByTenantIdAndExtractionResultId(tenantId, extraction.getId()).stream().map(f -> new FieldReview(f.getId(), f.getFieldName(), f.getRawValue(), f.getNormalizedValue(), f.getConfidence(), f.getValidationStatus(), evidence(evidence.get(f.getSourceEvidenceId())))).toList(),
        lineRepository.findByTenantIdAndExtractionResultId(tenantId, extraction.getId()).stream().map(l -> new LineItemReview(l.getId(), l.getLineNumber(), l.getRawSku(), l.getRawDescription(), l.getRawQuantity(), l.getNormalizedQuantity(), l.getRawUom(), l.getNormalizedUom(), l.getRequestedDate(), l.getConfidence(), l.getValidationStatus(), evidence(evidence.get(l.getSourceEvidenceId())))).toList(),
        noteRepository.findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(tenantId, "REVIEW_CASE", reviewCase.getId()).stream().map(n -> new NoteReview(n.getId(), n.getNoteText(), n.getCreatedAt())).toList(),
        timeline,
        timeline.stream().filter(a -> correctionAction(a.actionType())).toList(),
        readiness.draftPreparationAllowed(),
        blockingReasons,
        readiness);
  }

  @Transactional public ReviewCaseDetail startReview(UUID id, UUID actorUserId) { return transition(id, "IN_REVIEW", actorUserId, "REVIEW_STARTED", "Review started"); }
  @Transactional public ReviewCaseDetail approveForNextStep(UUID id, UUID actorUserId) { return transition(id, "APPROVED_FOR_NEXT_STEP", actorUserId, "REVIEW_APPROVED_FOR_NEXT_STEP", "Review approved for next stage only"); }
  @Transactional public ReviewCaseDetail reject(UUID id, UUID actorUserId) { return transition(id, "REJECTED", actorUserId, "REVIEW_REJECTED", "Review rejected"); }
  @Transactional public ReviewCaseDetail requestCorrection(UUID id, UUID actorUserId) { return transition(id, "NEEDS_CORRECTION", actorUserId, "REVIEW_CORRECTION_REQUESTED", "Correction requested"); }
  @Transactional public ReviewCaseDetail escalate(UUID id, UUID actorUserId) { return transition(id, "ESCALATED", actorUserId, "REVIEW_ESCALATED", "Review escalated"); }

  @Transactional
  public ReviewCaseDetail addNote(UUID id, String noteText, UUID createdBy) {
    ExceptionCase reviewCase = getCase(id);
    noteService.add("REVIEW_CASE", reviewCase.getId(), noteText, createdBy);
    actionService.record(createdBy, "REVIEW_CASE", reviewCase.getId(), "INTERNAL_NOTE_ADDED", "Internal review note added", "{}");
    return detail(id);
  }

  @Transactional
  public ReviewCaseDetail confirmCandidateMatch(UUID id, UUID suggestedFixId, UUID actorUserId) {
    ExceptionCase reviewCase = getCase(id);
    SuggestedFix fix = suggestedFixService.accept(suggestedFixId);
    if (!reviewCase.getValidationRunId().equals(fix.getValidationRunId())) {
      throw new IllegalArgumentException("Suggested action does not belong to review case");
    }
    actionService.record(actorUserId, "REVIEW_CASE", reviewCase.getId(), "ISSUE_CANDIDATE_REVIEWED", "Candidate match confirmed inside review state only", "{\"suggestedFixId\":\"" + suggestedFixId + "\"}");
    return detail(id);
  }

  private ReviewCaseDetail transition(UUID id, String status, UUID actorUserId, String actionType, String message) {
    ExceptionCase reviewCase = getCase(id);
    reviewCase.setStatus(status, clock.instant());
    caseRepository.save(reviewCase);
    actionService.record(actorUserId, "REVIEW_CASE", reviewCase.getId(), actionType, message, "{\"status\":\"" + status + "\"}");
    return detail(id);
  }

  private ExceptionCase getCase(UUID id) {
    return caseRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow();
  }

  private boolean reviewRequired(UUID tenantId, UUID validationRunId) {
    return validationIssueRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, validationRunId).stream().anyMatch(i -> "OPEN".equals(i.getStatus()))
        || approvalRequirementRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, validationRunId).stream().anyMatch(a -> "OPEN".equals(a.getStatus()));
  }

  private ReviewCaseSummary summary(ExceptionCase c) {
    return new ReviewCaseSummary(c.getId(), c.getCaseNumber(), c.getValidationRunId(), c.getExtractionResultId(), c.getStatus(), c.getPriority(), c.getSeverity(), c.getSummary(), c.getCreatedAt());
  }

  private List<IssueGroup> issueGroups(List<ValidationIssue> issues) {
    Map<String, List<IssueReview>> grouped = new LinkedHashMap<>();
    GROUP_ORDER.forEach(group -> grouped.put(group, new ArrayList<>()));
    issues.forEach(issue -> grouped.get(group(issue.getIssueType())).add(new IssueReview(issue.getId(), issue.getExtractedLineItemId(), issue.getExtractedFieldId(), issue.getIssueType(), issue.getSeverity(), issue.getStatus(), issue.getMessage(), suggestedAction(issue.getIssueType()), risk(issue.getSeverity()))));
    return grouped.entrySet().stream().filter(e -> !e.getValue().isEmpty()).map(e -> new IssueGroup(e.getKey(), e.getValue())).toList();
  }

  private List<IssueStatusReview> issueStatuses(List<ValidationIssue> issues, List<ApprovalRequirement> approvals, List<BlockingReason> blockingReasons) {
    return issues.stream().map(issue -> {
      boolean pendingApproval = approvals.stream().anyMatch(approval -> "OPEN".equals(approval.getStatus()) && (approval.getExtractedLineItemId() == null || approval.getExtractedLineItemId().equals(issue.getExtractedLineItemId())));
      boolean blocking = blockingReasons.stream().anyMatch(reason -> reason.issueCode().equals(issue.getIssueType()));
      return new IssueStatusReview(issue.getId(), issue.getIssueType(), issue.getStatus(), blocking, pendingApproval, lifecycle(issue.getStatus(), pendingApproval, blocking));
    }).toList();
  }

  private List<ProductCandidateReview> productCandidates(UUID tenantId, UUID validationRunId) {
    List<ProductCandidateReview> candidates = new ArrayList<>();
    for (ProductMatchResult match : productMatchResultRepository.findByTenantIdAndValidationRunId(tenantId, validationRunId)) {
      if (match.getMatchedProductId() != null) {
        productRepository.findByIdAndTenantIdAndDeletedAtIsNull(match.getMatchedProductId(), tenantId)
            .ifPresent(product -> candidates.add(candidate(match, product, match.getStatus())));
      }
      Object candidateIds = match.getCandidatesJson() == null ? null : jsonSupport.parseObject(match.getCandidatesJson()).get("candidateProductIds");
      if (candidateIds instanceof List<?> ids) {
        ids.stream().map(String::valueOf).map(UUID::fromString).forEach(id -> productRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId).ifPresent(product -> candidates.add(candidate(match, product, "CANDIDATE"))));
      }
    }
    return candidates.stream().collect(Collectors.toMap(c -> c.extractedLineItemId() + ":" + c.productId(), Function.identity(), (a, b) -> a, LinkedHashMap::new)).values().stream().toList();
  }

  private ProductCandidateReview candidate(ProductMatchResult match, Product product, String status) {
    return new ProductCandidateReview(match.getExtractedLineItemId(), product.getId(), product.getSku(), product.getName(), match.getMatchType(), match.getConfidence(), status);
  }

  private SubstituteCandidateReview substituteReview(UUID tenantId, SubstituteCandidate candidate) {
    Product substitute = productRepository.findByIdAndTenantIdAndDeletedAtIsNull(candidate.getSubstituteProductId(), tenantId).orElse(null);
    return new SubstituteCandidateReview(candidate.getId(), candidate.getExtractedLineItemId(), candidate.getSourceProductId(), candidate.getSubstituteProductId(), substitute == null ? null : substitute.getSku(), substitute == null ? null : substitute.getName(), candidate.getSubstituteType(), candidate.getRiskLevel(), candidate.getRankScore(), candidate.isRequiresApproval(), candidate.getStatus(), candidate.getInventoryStatus(), candidate.getMarginStatus(), candidate.getReason());
  }

  private String lifecycle(String status, boolean pendingApproval, boolean blocking) {
    if ("OPEN".equals(status) && blocking) return "still blocking";
    if ("OPEN".equals(status) && pendingApproval) return "pending approval";
    return switch (status) {
      case "OPEN" -> "unresolved";
      case "CORRECTED" -> "corrected";
      case "ACKNOWLEDGED" -> "acknowledged";
      case "OVERRIDDEN" -> "overridden";
      default -> status.toLowerCase();
    };
  }

  private boolean correctionAction(String actionType) {
    return actionType.contains("CORRECTED") || actionType.contains("SUBSTITUTE") || actionType.contains("ACKNOWLEDGED") || actionType.contains("OVERRIDDEN") || actionType.contains("PREVIEW") || actionType.contains("PREPARED") || actionType.contains("BLOCKED");
  }

  private String group(String issueType) {
    return switch (issueType) {
      case "CUSTOMER_NOT_FOUND", "CUSTOMER_AMBIGUOUS" -> "CUSTOMER";
      case "LOW_EXTRACTION_CONFIDENCE", "REQUESTED_DATE_INVALID", "NEEDS_HUMAN_REVIEW" -> "DOCUMENT";
      case "PRODUCT_NOT_FOUND", "PRODUCT_ALIAS_MATCHED", "PRODUCT_AMBIGUOUS", "INVALID_UOM", "UOM_NORMALIZED", "INVALID_QUANTITY" -> "LINE_PRODUCT";
      case "PRICE_NOT_FOUND", "DISCOUNT_REQUIRES_APPROVAL", "MARGIN_BELOW_GUARDRAIL" -> "PRICING";
      case "OUT_OF_STOCK", "LOW_STOCK" -> "INVENTORY";
      case "SUBSTITUTE_AVAILABLE", "SUBSTITUTE_REQUIRES_APPROVAL", "COMPATIBILITY_UNVERIFIED" -> "SUBSTITUTION_COMPATIBILITY";
      default -> "POLICY_APPROVAL";
    };
  }

  private String suggestedAction(String issueType) {
    return switch (issueType) {
      case "CUSTOMER_NOT_FOUND", "CUSTOMER_AMBIGUOUS" -> "confirm customer match";
      case "PRODUCT_NOT_FOUND", "PRODUCT_ALIAS_MATCHED", "PRODUCT_AMBIGUOUS" -> "select product candidate";
      case "INVALID_UOM", "UOM_NORMALIZED" -> "normalize UOM";
      case "INVALID_QUANTITY" -> "adjust quantity";
      case "OUT_OF_STOCK", "LOW_STOCK", "SUBSTITUTE_AVAILABLE" -> "select substitute candidate";
      case "DISCOUNT_REQUIRES_APPROVAL", "MARGIN_BELOW_GUARDRAIL", "SUBSTITUTE_REQUIRES_APPROVAL", "LOW_EXTRACTION_CONFIDENCE" -> "request manager approval";
      case "REQUESTED_DATE_INVALID" -> "mark as needs manual follow-up";
      default -> "escalate to supervisor";
    };
  }

  private String riskLevel(List<ValidationIssue> issues, List<ApprovalRequirement> approvals) {
    if (issues.stream().anyMatch(i -> "CRITICAL".equals(i.getSeverity())) || approvals.stream().anyMatch(a -> "CRITICAL".equals(a.getSeverity()))) return "HIGH";
    if (issues.stream().anyMatch(i -> "ERROR".equals(i.getSeverity())) || approvals.stream().anyMatch(a -> "ERROR".equals(a.getSeverity()))) return "MEDIUM";
    return issues.isEmpty() && approvals.isEmpty() ? "LOW" : "REVIEW";
  }

  private String risk(String severity) {
    return switch (severity) {
      case "CRITICAL" -> "HIGH";
      case "ERROR" -> "MEDIUM";
      case "WARNING" -> "REVIEW";
      default -> "LOW";
    };
  }

  private EvidenceReference evidence(SourceEvidence evidence) {
    if (evidence == null) return null;
    return new EvidenceReference(evidence.getId(), evidence.getSourceType(), evidence.getSourceId(), evidence.getEvidenceType(), evidence.getPageNumber(), evidence.getStartOffset(), evidence.getEndOffset(), evidence.getSnippet());
  }
}
