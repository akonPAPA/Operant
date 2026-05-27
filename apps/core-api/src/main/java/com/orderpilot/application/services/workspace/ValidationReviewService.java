package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage6Dtos.ReviewCaseDetail;
import com.orderpilot.api.dto.Stage6Dtos.ReviewCaseSummary;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.validation.ValidationRunService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.validation.ApprovalRequirement;
import com.orderpilot.domain.validation.ApprovalRequirementRepository;
import com.orderpilot.domain.validation.ProductMatchResult;
import com.orderpilot.domain.validation.ProductMatchResultRepository;
import com.orderpilot.domain.validation.SubstituteCandidate;
import com.orderpilot.domain.validation.SubstituteCandidateRepository;
import com.orderpilot.domain.validation.UomNormalizationResultRepository;
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import com.orderpilot.domain.validation.ValidationRun;
import com.orderpilot.domain.validation.ValidationRunRepository;
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ValidationReviewService {
  private final ExtractionResultRepository extractionResultRepository;
  private final ValidationRunRepository validationRunRepository;
  private final ValidationRunService validationRunService;
  private final ExceptionCaseRepository caseRepository;
  private final ExceptionCaseService exceptionCaseService;
  private final OperatorReviewService operatorReviewService;
  private final ApprovalWorkflowService approvalWorkflowService;
  private final AuditEventService auditEventService;
  private final OperatorActionService actionService;
  private final ValidationIssueRepository issueRepository;
  private final ApprovalRequirementRepository approvalRepository;
  private final ExtractedLineItemRepository lineRepository;
  private final UomNormalizationResultRepository uomRepository;
  private final ProductMatchResultRepository productMatchRepository;
  private final ProductRepository productRepository;
  private final SubstituteCandidateRepository substituteCandidateRepository;
  private final Clock clock;

  public ValidationReviewService(ExtractionResultRepository extractionResultRepository, ValidationRunRepository validationRunRepository, ValidationRunService validationRunService, ExceptionCaseRepository caseRepository, ExceptionCaseService exceptionCaseService, OperatorReviewService operatorReviewService, ApprovalWorkflowService approvalWorkflowService, AuditEventService auditEventService, OperatorActionService actionService, ValidationIssueRepository issueRepository, ApprovalRequirementRepository approvalRepository, ExtractedLineItemRepository lineRepository, UomNormalizationResultRepository uomRepository, ProductMatchResultRepository productMatchRepository, ProductRepository productRepository, SubstituteCandidateRepository substituteCandidateRepository, Clock clock) {
    this.extractionResultRepository = extractionResultRepository;
    this.validationRunRepository = validationRunRepository;
    this.validationRunService = validationRunService;
    this.caseRepository = caseRepository;
    this.exceptionCaseService = exceptionCaseService;
    this.operatorReviewService = operatorReviewService;
    this.approvalWorkflowService = approvalWorkflowService;
    this.auditEventService = auditEventService;
    this.actionService = actionService;
    this.issueRepository = issueRepository;
    this.approvalRepository = approvalRepository;
    this.lineRepository = lineRepository;
    this.uomRepository = uomRepository;
    this.productMatchRepository = productMatchRepository;
    this.productRepository = productRepository;
    this.substituteCandidateRepository = substituteCandidateRepository;
    this.clock = clock;
  }

  @Transactional
  public ReviewCaseDetail createForExtractionResult(UUID extractionResultId) {
    UUID tenantId = TenantContext.requireTenantId();
    ExtractionResult extraction = extractionResultRepository.findByIdAndTenantId(extractionResultId, tenantId).orElseThrow();
    ValidationRun run = validationRunRepository.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, extraction.getId()).stream()
        .findFirst()
        .orElseGet(() -> validationRunService.run(extraction.getId(), "FULL"));
    ExceptionCase reviewCase = caseRepository.findFirstByTenantIdAndValidationRunIdOrderByCreatedAtDesc(tenantId, run.getId())
        .orElseGet(() -> exceptionCaseService.createFromValidation(run.getId()));
    auditEventService.record("VALIDATION_REVIEW_CASE_CREATED", "REVIEW_CASE", reviewCase.getId().toString(), null, "{\"validationRunId\":\"" + run.getId() + "\",\"extractionResultId\":\"" + extraction.getId() + "\"}");
    return operatorReviewService.detail(reviewCase.getId());
  }

  @Transactional(readOnly = true)
  public List<ReviewCaseSummary> list() {
    return operatorReviewService.list();
  }

  @Transactional(readOnly = true)
  public ReviewCaseDetail get(UUID reviewCaseId) {
    return operatorReviewService.detail(reviewCaseId);
  }

  @Transactional
  public ReviewCaseDetail approveForDraft(UUID reviewCaseId, UUID actorId) {
    ExceptionCase reviewCase = getCase(reviewCaseId);
    reviewCase.setStatus("APPROVED_FOR_DRAFT", clock.instant());
    caseRepository.save(reviewCase);
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "VALIDATION_REVIEW_APPROVED_FOR_DRAFT", "Review case approved for internal draft preparation", "{\"validationRunId\":\"" + reviewCase.getValidationRunId() + "\"}");
    auditEventService.record("VALIDATION_REVIEW_APPROVED_FOR_DRAFT", "REVIEW_CASE", reviewCase.getId().toString(), actorId, "{\"validationRunId\":\"" + reviewCase.getValidationRunId() + "\"}");
    return operatorReviewService.detail(reviewCase.getId());
  }

  @Transactional
  public ReviewCaseDetail reject(UUID reviewCaseId, UUID actorId) {
    ExceptionCase reviewCase = getCase(reviewCaseId);
    reviewCase.setStatus("REJECTED", clock.instant());
    caseRepository.save(reviewCase);
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "VALIDATION_REVIEW_REJECTED", "Review case rejected", "{\"validationRunId\":\"" + reviewCase.getValidationRunId() + "\"}");
    auditEventService.record("VALIDATION_REVIEW_REJECTED", "REVIEW_CASE", reviewCase.getId().toString(), actorId, "{\"validationRunId\":\"" + reviewCase.getValidationRunId() + "\"}");
    return operatorReviewService.detail(reviewCase.getId());
  }

  @Transactional
  public ReviewCaseDetail correctUom(UUID reviewCaseId, UUID lineItemId, String normalizedUom, UUID actorId) {
    ExceptionCase reviewCase = getCase(reviewCaseId);
    requireText(normalizedUom, "normalizedUom is required");
    ExtractedLineItem line = lineRepository.findByIdAndTenantId(lineItemId, TenantContext.requireTenantId()).orElseThrow();
    requireLineBelongsToReviewCase(reviewCase, line);
    line.correctUom(normalizedUom.trim().toUpperCase(), clock.instant());
    lineRepository.save(line);
    uomRepository.findFirstByTenantIdAndValidationRunIdAndExtractedLineItemId(TenantContext.requireTenantId(), reviewCase.getValidationRunId(), lineItemId)
        .ifPresent(result -> result.correct(normalizedUom.trim().toUpperCase(), clock.instant()));
    resolveLineIssues(reviewCase, lineItemId, List.of("INVALID_UOM"), "CORRECTED");
    resolveLineApprovals(reviewCase, lineItemId, List.of("INVALID_UOM_REQUIRES_REVIEW"), "CORRECTED");
    markCaseNeedsReview(reviewCase);
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "VALIDATION_REVIEW_UOM_CORRECTED", "UOM corrected in validation review", "{\"lineItemId\":\"" + lineItemId + "\",\"normalizedUom\":\"" + escape(normalizedUom) + "\"}");
    return operatorReviewService.detail(reviewCase.getId());
  }

  @Transactional
  public ReviewCaseDetail correctQuantity(UUID reviewCaseId, UUID lineItemId, BigDecimal normalizedQuantity, UUID actorId) {
    ExceptionCase reviewCase = getCase(reviewCaseId);
    if (normalizedQuantity == null || normalizedQuantity.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("normalizedQuantity must be positive");
    ExtractedLineItem line = lineRepository.findByIdAndTenantId(lineItemId, TenantContext.requireTenantId()).orElseThrow();
    requireLineBelongsToReviewCase(reviewCase, line);
    line.correctQuantity(normalizedQuantity, clock.instant());
    lineRepository.save(line);
    resolveLineIssues(reviewCase, lineItemId, List.of("INVALID_QUANTITY"), "CORRECTED");
    markCaseNeedsReview(reviewCase);
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "VALIDATION_REVIEW_QUANTITY_CORRECTED", "Quantity corrected in validation review", "{\"lineItemId\":\"" + lineItemId + "\",\"normalizedQuantity\":\"" + normalizedQuantity + "\"}");
    return operatorReviewService.detail(reviewCase.getId());
  }

  @Transactional
  public ReviewCaseDetail mapProduct(UUID reviewCaseId, UUID lineItemId, UUID productId, UUID actorId) {
    ExceptionCase reviewCase = getCase(reviewCaseId);
    UUID tenantId = TenantContext.requireTenantId();
    ExtractedLineItem line = lineRepository.findByIdAndTenantId(lineItemId, tenantId).orElseThrow();
    requireLineBelongsToReviewCase(reviewCase, line);
    productRepository.findByIdAndTenantIdAndDeletedAtIsNull(productId, tenantId).orElseThrow();
    ProductMatchResult match = productMatchRepository.findFirstByTenantIdAndValidationRunIdAndExtractedLineItemId(tenantId, reviewCase.getValidationRunId(), lineItemId)
        .orElseThrow();
    match.confirmMatch(productId, "OPERATOR_SELECTED", clock.instant());
    productMatchRepository.save(match);
    resolveLineIssues(reviewCase, lineItemId, List.of("PRODUCT_AMBIGUOUS", "PRODUCT_NOT_FOUND"), "CORRECTED");
    resolveLineApprovals(reviewCase, lineItemId, List.of("PRODUCT_AMBIGUOUS"), "CORRECTED");
    markCaseNeedsReview(reviewCase);
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "VALIDATION_REVIEW_PRODUCT_MAPPED", "Product candidate selected in validation review", "{\"lineItemId\":\"" + lineItemId + "\",\"productId\":\"" + productId + "\"}");
    return operatorReviewService.detail(reviewCase.getId());
  }

  @Transactional
  public ReviewCaseDetail selectSubstitute(UUID reviewCaseId, UUID candidateId, UUID actorId, String reason) {
    ExceptionCase reviewCase = getCase(reviewCaseId);
    SubstituteCandidate candidate = substituteCandidateRepository.findByIdAndTenantId(candidateId, TenantContext.requireTenantId()).orElseThrow();
    requireCandidateBelongsToReviewCase(reviewCase, candidate);
    if (candidate.getStatus().contains("BLOCKED")) {
      actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "VALIDATION_REVIEW_BLOCKED_SUBSTITUTE_SELECTION_REJECTED", "Blocked substitute selection rejected", "{\"candidateId\":\"" + candidateId + "\"}");
      auditEventService.record("VALIDATION_REVIEW_BLOCKED_SUBSTITUTE_SELECTION_REJECTED", "REVIEW_CASE", reviewCase.getId().toString(), actorId, "{\"candidateId\":\"" + candidateId + "\"}");
      throw new IllegalArgumentException("Blocked substitute cannot be selected");
    }
    candidate.setStatus("SELECTED", clock.instant());
    substituteCandidateRepository.save(candidate);
    resolveLineIssues(reviewCase, candidate.getExtractedLineItemId(), List.of("OUT_OF_STOCK", "LOW_STOCK", "SUBSTITUTE_AVAILABLE"), "CORRECTED");
    if (!candidate.isRequiresApproval()) {
      resolveLineIssues(reviewCase, candidate.getExtractedLineItemId(), List.of("SUBSTITUTE_REQUIRES_APPROVAL"), "CORRECTED");
      resolveLineApprovals(reviewCase, candidate.getExtractedLineItemId(), List.of("SUBSTITUTE_REQUIRES_APPROVAL"), "CORRECTED");
    }
    markCaseNeedsReview(reviewCase);
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "VALIDATION_REVIEW_SUBSTITUTE_SELECTED", "Substitute candidate selected in validation review", "{\"candidateId\":\"" + candidateId + "\",\"reason\":\"" + escape(reason) + "\"}");
    return operatorReviewService.detail(reviewCase.getId());
  }

  @Transactional
  public ReviewCaseDetail rejectSubstitute(UUID reviewCaseId, UUID candidateId, UUID actorId, String reason) {
    ExceptionCase reviewCase = getCase(reviewCaseId);
    SubstituteCandidate candidate = substituteCandidateRepository.findByIdAndTenantId(candidateId, TenantContext.requireTenantId()).orElseThrow();
    requireCandidateBelongsToReviewCase(reviewCase, candidate);
    candidate.setStatus("REJECTED", clock.instant());
    substituteCandidateRepository.save(candidate);
    markCaseNeedsReview(reviewCase);
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "VALIDATION_REVIEW_SUBSTITUTE_REJECTED", "Substitute candidate rejected in validation review", "{\"candidateId\":\"" + candidateId + "\",\"reason\":\"" + escape(reason) + "\"}");
    return operatorReviewService.detail(reviewCase.getId());
  }

  @Transactional
  public ReviewCaseDetail acknowledgeIssue(UUID reviewCaseId, UUID issueId, UUID actorId) {
    ExceptionCase reviewCase = getCase(reviewCaseId);
    ValidationIssue issue = issueRepository.findByIdAndTenantId(issueId, TenantContext.requireTenantId()).orElseThrow();
    requireIssueBelongsToReviewCase(reviewCase, issue);
    if (isHardBlocking(issue)) {
      throw new IllegalArgumentException("Blocking issue requires correction or override reason");
    }
    issue.setStatus("ACKNOWLEDGED", clock.instant());
    issueRepository.save(issue);
    markCaseNeedsReview(reviewCase);
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "VALIDATION_REVIEW_ISSUE_ACKNOWLEDGED", "Non-blocking validation issue acknowledged", "{\"issueId\":\"" + issueId + "\"}");
    return operatorReviewService.detail(reviewCase.getId());
  }

  @Transactional
  public ReviewCaseDetail overrideIssue(UUID reviewCaseId, UUID issueId, UUID actorId, String reason) {
    ExceptionCase reviewCase = getCase(reviewCaseId);
    requireText(reason, "Override reason is required");
    ValidationIssue issue = issueRepository.findByIdAndTenantId(issueId, TenantContext.requireTenantId()).orElseThrow();
    requireIssueBelongsToReviewCase(reviewCase, issue);
    issue.setStatus("OVERRIDDEN", clock.instant());
    issueRepository.save(issue);
    approvalRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(TenantContext.requireTenantId(), reviewCase.getValidationRunId()).stream()
        .filter(approval -> "OPEN".equals(approval.getStatus()))
        .filter(approval -> issue.getExtractedLineItemId() == null || issue.getExtractedLineItemId().equals(approval.getExtractedLineItemId()))
        .forEach(approval -> approval.setStatus("OVERRIDDEN", clock.instant()));
    markCaseNeedsReview(reviewCase);
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "VALIDATION_REVIEW_ISSUE_OVERRIDDEN", "Validation issue overridden with operator reason", "{\"issueId\":\"" + issueId + "\",\"reason\":\"" + escape(reason) + "\"}");
    return operatorReviewService.detail(reviewCase.getId());
  }

  @Transactional
  public ReviewCaseDetail approveApproval(UUID reviewCaseId, UUID approvalId, UUID actorId, String reason) {
    ExceptionCase reviewCase = getCase(reviewCaseId);
    ApprovalRequirement approval = approvalRepository.findByIdAndTenantId(approvalId, TenantContext.requireTenantId()).orElseThrow();
    requireApprovalBelongsToReviewCase(reviewCase, approval);
    if ("SUBSTITUTE_BLOCKED_BY_CUSTOMER_POLICY".equals(approval.getRequirementType())) {
      throw new IllegalArgumentException("Blocked substitute approval cannot be approved");
    }
    if (requiresDecisionReason(approval) && (reason == null || reason.isBlank())) {
      throw new IllegalArgumentException("Approval reason is required for risky approval");
    }
    approvalWorkflowService.decide("APPROVAL_REQUIREMENT", approvalId, "APPROVED", reason, actorId);
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "VALIDATION_REVIEW_APPROVAL_APPROVED", "Manager approval requirement approved", "{\"approvalRequirementId\":\"" + approvalId + "\",\"requirementType\":\"" + escape(approval.getRequirementType()) + "\",\"reason\":\"" + escape(reason) + "\"}");
    return operatorReviewService.detail(reviewCase.getId());
  }

  @Transactional
  public ReviewCaseDetail rejectApproval(UUID reviewCaseId, UUID approvalId, UUID actorId, String reason) {
    ExceptionCase reviewCase = getCase(reviewCaseId);
    ApprovalRequirement approval = approvalRepository.findByIdAndTenantId(approvalId, TenantContext.requireTenantId()).orElseThrow();
    requireApprovalBelongsToReviewCase(reviewCase, approval);
    requireText(reason, "Rejection reason is required");
    approvalWorkflowService.decide("APPROVAL_REQUIREMENT", approvalId, "REJECTED", reason, actorId);
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "VALIDATION_REVIEW_APPROVAL_REJECTED", "Manager approval requirement rejected", "{\"approvalRequirementId\":\"" + approvalId + "\",\"requirementType\":\"" + escape(approval.getRequirementType()) + "\",\"reason\":\"" + escape(reason) + "\"}");
    return operatorReviewService.detail(reviewCase.getId());
  }

  private ExceptionCase getCase(UUID reviewCaseId) {
    ExceptionCase reviewCase = caseRepository.findByIdAndTenantId(reviewCaseId, TenantContext.requireTenantId()).orElseThrow();
    if (reviewCase.getValidationRunId() == null || reviewCase.getExtractionResultId() == null) {
      throw new IllegalArgumentException("Review case is not validation-backed. Use the bot operator handoff view for sourceType=" + reviewCase.getSourceType());
    }
    return reviewCase;
  }

  private void requireLineBelongsToReviewCase(ExceptionCase reviewCase, ExtractedLineItem line) {
    if (!reviewCase.getExtractionResultId().equals(line.getExtractionResultId())) {
      throw new IllegalArgumentException("Line item does not belong to review case");
    }
  }

  private void requireCandidateBelongsToReviewCase(ExceptionCase reviewCase, SubstituteCandidate candidate) {
    if (!reviewCase.getValidationRunId().equals(candidate.getValidationRunId())) {
      throw new IllegalArgumentException("Substitute candidate does not belong to review case");
    }
  }

  private void requireIssueBelongsToReviewCase(ExceptionCase reviewCase, ValidationIssue issue) {
    if (!reviewCase.getValidationRunId().equals(issue.getValidationRunId())) {
      throw new IllegalArgumentException("Validation issue does not belong to review case");
    }
  }

  private void requireApprovalBelongsToReviewCase(ExceptionCase reviewCase, ApprovalRequirement approval) {
    if (!reviewCase.getValidationRunId().equals(approval.getValidationRunId())) {
      throw new IllegalArgumentException("Approval requirement does not belong to review case");
    }
  }

  private boolean requiresDecisionReason(ApprovalRequirement approval) {
    return List.of("DISCOUNT_REQUIRES_APPROVAL", "MARGIN_BELOW_GUARDRAIL", "SUBSTITUTE_REQUIRES_APPROVAL", "NEEDS_HUMAN_REVIEW").contains(approval.getRequirementType());
  }

  private void resolveLineIssues(ExceptionCase reviewCase, UUID lineItemId, List<String> issueTypes, String status) {
    issueRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(TenantContext.requireTenantId(), reviewCase.getValidationRunId()).stream()
        .filter(issue -> "OPEN".equals(issue.getStatus()))
        .filter(issue -> lineItemId.equals(issue.getExtractedLineItemId()))
        .filter(issue -> issueTypes.contains(issue.getIssueType()))
        .forEach(issue -> issue.setStatus(status, clock.instant()));
  }

  private void resolveLineApprovals(ExceptionCase reviewCase, UUID lineItemId, List<String> requirementTypes, String status) {
    approvalRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(TenantContext.requireTenantId(), reviewCase.getValidationRunId()).stream()
        .filter(approval -> "OPEN".equals(approval.getStatus()))
        .filter(approval -> lineItemId.equals(approval.getExtractedLineItemId()))
        .filter(approval -> requirementTypes.contains(approval.getRequirementType()))
        .forEach(approval -> approval.setStatus(status, clock.instant()));
  }

  private boolean isHardBlocking(ValidationIssue issue) {
    return List.of("INVALID_UOM", "PRODUCT_AMBIGUOUS", "PRODUCT_NOT_FOUND", "INVALID_QUANTITY").contains(issue.getIssueType())
        || "CRITICAL".equals(issue.getSeverity());
  }

  private void markCaseNeedsReview(ExceptionCase reviewCase) {
    reviewCase.setStatus("NEEDS_REVIEW_AFTER_CORRECTION", clock.instant());
    caseRepository.save(reviewCase);
  }

  private void requireText(String value, String message) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
