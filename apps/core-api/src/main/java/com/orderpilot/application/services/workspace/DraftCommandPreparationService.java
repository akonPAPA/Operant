package com.orderpilot.application.services.workspace;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.api.dto.Stage6Dtos.BlockingReason;
import com.orderpilot.api.dto.Stage6Dtos.DraftPreview;
import com.orderpilot.api.dto.Stage6Dtos.DraftPreviewLine;
import com.orderpilot.api.dto.Stage6Dtos.DraftPreparationResult;
import com.orderpilot.api.dto.Stage6Dtos.ApprovalRequirementReview;
import com.orderpilot.api.dto.Stage6Dtos.ReviewReadiness;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.validation.*;
import com.orderpilot.domain.workspace.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DraftCommandPreparationService {
  private static final Set<String> HARD_BLOCKING_ISSUES = Set.of("INVALID_UOM", "PRODUCT_AMBIGUOUS", "PRODUCT_NOT_FOUND", "INVALID_QUANTITY");
  private static final Set<String> APPROVAL_BACKED_ISSUES = Set.of("LOW_EXTRACTION_CONFIDENCE", "MARGIN_BELOW_GUARDRAIL", "DISCOUNT_REQUIRES_APPROVAL", "SUBSTITUTE_REQUIRES_APPROVAL");
  private static final Set<String> APPROVAL_BACKED_REQUIREMENTS = Set.of("NEEDS_HUMAN_REVIEW", "MARGIN_BELOW_GUARDRAIL", "DISCOUNT_REQUIRES_APPROVAL", "SUBSTITUTE_REQUIRES_APPROVAL");
  private static final Set<String> RESOLVED_APPROVAL_STATUSES = Set.of("APPROVED", "CORRECTED", "OVERRIDDEN", "ACKNOWLEDGED");

  private final ExceptionCaseRepository caseRepository;
  private final ValidationIssueRepository issueRepository;
  private final ApprovalRequirementRepository approvalRepository;
  private final ProductMatchResultRepository productMatchRepository;
  private final SubstituteCandidateRepository substituteCandidateRepository;
  private final ValidationRunRepository validationRunRepository;
  private final ExtractedLineItemRepository lineRepository;
  private final ProductRepository productRepository;
  private final PriceCheckResultRepository priceRepository;
  private final UomNormalizationResultRepository uomRepository;
  private final MarginCheckResultRepository marginRepository;
  private final DiscountCheckResultRepository discountRepository;
  private final InventoryCheckResultRepository inventoryRepository;
  private final ExtractionResultRepository extractionResultRepository;
  private final DraftQuoteRepository draftQuoteRepository;
  private final DraftOrderRepository draftOrderRepository;
  private final DraftQuoteService draftQuoteService;
  private final DraftOrderService draftOrderService;
  private final AuditEventService auditEventService;
  private final OperatorActionService actionService;

  public DraftCommandPreparationService(ExceptionCaseRepository caseRepository, ValidationIssueRepository issueRepository, ApprovalRequirementRepository approvalRepository, ProductMatchResultRepository productMatchRepository, SubstituteCandidateRepository substituteCandidateRepository, ValidationRunRepository validationRunRepository, ExtractedLineItemRepository lineRepository, ProductRepository productRepository, PriceCheckResultRepository priceRepository, UomNormalizationResultRepository uomRepository, MarginCheckResultRepository marginRepository, DiscountCheckResultRepository discountRepository, InventoryCheckResultRepository inventoryRepository, ExtractionResultRepository extractionResultRepository, DraftQuoteRepository draftQuoteRepository, DraftOrderRepository draftOrderRepository, DraftQuoteService draftQuoteService, DraftOrderService draftOrderService, AuditEventService auditEventService, OperatorActionService actionService) {
    this.caseRepository = caseRepository;
    this.issueRepository = issueRepository;
    this.approvalRepository = approvalRepository;
    this.productMatchRepository = productMatchRepository;
    this.substituteCandidateRepository = substituteCandidateRepository;
    this.validationRunRepository = validationRunRepository;
    this.lineRepository = lineRepository;
    this.productRepository = productRepository;
    this.priceRepository = priceRepository;
    this.uomRepository = uomRepository;
    this.marginRepository = marginRepository;
    this.discountRepository = discountRepository;
    this.inventoryRepository = inventoryRepository;
    this.extractionResultRepository = extractionResultRepository;
    this.draftQuoteRepository = draftQuoteRepository;
    this.draftOrderRepository = draftOrderRepository;
    this.draftQuoteService = draftQuoteService;
    this.draftOrderService = draftOrderService;
    this.auditEventService = auditEventService;
    this.actionService = actionService;
  }

  @Transactional
  public DraftQuote prepareDraftQuote(UUID reviewCaseId, UUID actorId) {
    ExceptionCase reviewCase = caseRepository.findByIdAndTenantId(reviewCaseId, TenantContext.requireTenantId()).orElseThrow();
    ensureAllowed(reviewCase, actorId, "DRAFT_QUOTE");
    DraftQuote quote = draftQuoteService.createFromValidation(reviewCase.getValidationRunId());
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "DRAFT_QUOTE_PREPARED", "Internal draft quote prepared from review case", "{\"draftQuoteId\":\"" + quote.getId() + "\"}");
    auditEventService.record("DRAFT_QUOTE_PREPARATION_SUCCEEDED", "DRAFT_QUOTE", quote.getId().toString(), actorId, "{\"reviewCaseId\":\"" + reviewCase.getId() + "\",\"externalExecution\":\"DISABLED\"}");
    return quote;
  }

  @Transactional
  public DraftOrder prepareDraftOrder(UUID reviewCaseId, UUID actorId) {
    ExceptionCase reviewCase = caseRepository.findByIdAndTenantId(reviewCaseId, TenantContext.requireTenantId()).orElseThrow();
    ensureAllowed(reviewCase, actorId, "DRAFT_ORDER");
    DraftOrder order = draftOrderService.createFromValidation(reviewCase.getValidationRunId());
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "DRAFT_ORDER_PREPARED", "Internal draft order prepared from review case", "{\"draftOrderId\":\"" + order.getId() + "\",\"inventoryReservation\":\"DISABLED\"}");
    auditEventService.record("DRAFT_ORDER_PREPARATION_SUCCEEDED", "DRAFT_ORDER", order.getId().toString(), actorId, "{\"reviewCaseId\":\"" + reviewCase.getId() + "\",\"externalExecution\":\"DISABLED\",\"inventoryReservation\":\"DISABLED\"}");
    return order;
  }

  /**
   * OP-CAP-09A: prepare exactly one internal draft (quote or order) from an approved validation handoff (review case).
   * Intent-driven, tenant-scoped, idempotent, audited. Creates no final quote/order and triggers no external/ERP write.
   */
  @Transactional
  public DraftPreparationResult prepareDraft(UUID reviewCaseId, UUID actorId) {
    return prepareDraft(reviewCaseId, actorId, null);
  }

  /**
   * OP-CAP-15A: same idempotent, readiness-gated, audited internal draft preparation, but with an
   * operator-chosen target type ({@code QUOTE}/{@code ORDER}). A null/blank {@code requestedType}
   * falls back to the conservative document-intent mapping used by OP-CAP-09A. No final/approved
   * order, no external/ERP write.
   */
  @Transactional
  public DraftPreparationResult prepareDraft(UUID reviewCaseId, UUID actorId, String requestedType) {
    return prepareDraft(reviewCaseId, actorId, requestedType, null, null);
  }

  /**
   * OP-CAP-15B: same idempotent, readiness-gated, audited preparation, with an optional selected-line
   * subset (by extracted line item id; null = all eligible lines, validated by the caller) and a bounded
   * operator note. Selection narrows the included lines but never relaxes run-level readiness gating.
   */
  @Transactional
  public DraftPreparationResult prepareDraft(UUID reviewCaseId, UUID actorId, String requestedType, Set<UUID> selectedLineIds, String operatorNote) {
    UUID tenantId = TenantContext.requireTenantId();
    ExceptionCase reviewCase = caseRepository.findByIdAndTenantId(reviewCaseId, tenantId).orElseThrow();

    // Idempotency: a single internal draft per (tenant, source handoff). Return the existing draft without re-creating
    // (selected lines / operator note of a replay are ignored — the original draft is authoritative).
    Optional<DraftQuote> existingQuote = draftQuoteRepository.findFirstByTenantIdAndSourceExceptionCaseIdOrderByCreatedAtAsc(tenantId, reviewCaseId);
    if (existingQuote.isPresent()) {
      return alreadyPrepared("QUOTE", existingQuote.get().getId(), existingQuote.get().getStatus(), reviewCase, actorId);
    }
    Optional<DraftOrder> existingOrder = draftOrderRepository.findFirstByTenantIdAndSourceExceptionCaseIdOrderByCreatedAtAsc(tenantId, reviewCaseId);
    if (existingOrder.isPresent()) {
      return alreadyPrepared("ORDER", existingOrder.get().getId(), existingOrder.get().getStatus(), reviewCase, actorId);
    }

    // Operator-chosen type when provided; otherwise conservative intent mapping (fail closed on unknown/unsupported intent).
    String draftType = normalizeRequestedDraftType(requestedType);
    if (draftType == null) {
      draftType = resolveDraftType(reviewCase, tenantId);
    }
    // Reuse the validation/review readiness gate (DRAFT_PREPARATION_READY analog; BLOCKED/FAILED fail closed).
    ensureAllowed(reviewCase, actorId, "QUOTE".equals(draftType) ? "DRAFT_QUOTE" : "DRAFT_ORDER");

    if ("QUOTE".equals(draftType)) {
      DraftQuote quote = draftQuoteService.createFromValidation(reviewCase.getValidationRunId(), reviewCase.getId(), selectedLineIds, operatorNote);
      return prepared("QUOTE", quote.getId(), quote.getStatus(), reviewCase, actorId);
    }
    DraftOrder order = draftOrderService.createFromValidation(reviewCase.getValidationRunId(), reviewCase.getId(), selectedLineIds, operatorNote);
    return prepared("ORDER", order.getId(), order.getStatus(), reviewCase, actorId);
  }

  private String normalizeRequestedDraftType(String requestedType) {
    if (requestedType == null || requestedType.isBlank()) {
      return null;
    }
    String type = requestedType.trim().toUpperCase(java.util.Locale.ROOT);
    if (!"QUOTE".equals(type) && !"ORDER".equals(type)) {
      throw new IllegalArgumentException("Unsupported draft target type: " + requestedType);
    }
    return type;
  }

  private String resolveDraftType(ExceptionCase reviewCase, UUID tenantId) {
    UUID extractionResultId = reviewCase.getExtractionResultId();
    String intent = extractionResultId == null ? null
        : extractionResultRepository.findByIdAndTenantId(extractionResultId, tenantId).map(ExtractionResult::getDetectedIntent).orElse(null);
    if (intent == null) {
      throw new IllegalArgumentException("Draft preparation requires a validation-backed document intent");
    }
    return switch (intent) {
      case "RFQ" -> "QUOTE";
      case "PURCHASE_ORDER" -> "ORDER";
      default -> throw new IllegalArgumentException("Unsupported document intent for draft preparation: " + intent);
    };
  }

  private DraftPreparationResult prepared(String draftType, UUID draftId, String status, ExceptionCase reviewCase, UUID actorId) {
    String entityType = "QUOTE".equals(draftType) ? "DRAFT_QUOTE" : "DRAFT_ORDER";
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "DRAFT_PREPARED", "Internal " + draftType.toLowerCase() + " draft prepared from approved validation handoff", "{\"draftType\":\"" + draftType + "\",\"draftId\":\"" + draftId + "\"}");
    auditEventService.record("DRAFT_PREPARATION_SUCCEEDED", entityType, draftId.toString(), actorId, "{\"reviewCaseId\":\"" + reviewCase.getId() + "\",\"draftType\":\"" + draftType + "\",\"externalExecution\":\"DISABLED\"}");
    return new DraftPreparationResult(draftType, draftId, reviewCase.getId(), status, true, false, "DISABLED", "OPEN_OPERATOR_WORKSPACE");
  }

  private DraftPreparationResult alreadyPrepared(String draftType, UUID draftId, String status, ExceptionCase reviewCase, UUID actorId) {
    String entityType = "QUOTE".equals(draftType) ? "DRAFT_QUOTE" : "DRAFT_ORDER";
    auditEventService.record("DRAFT_PREPARATION_ALREADY_PREPARED", entityType, draftId.toString(), actorId, "{\"reviewCaseId\":\"" + reviewCase.getId() + "\",\"draftType\":\"" + draftType + "\",\"externalExecution\":\"DISABLED\"}");
    return new DraftPreparationResult(draftType, draftId, reviewCase.getId(), status, false, true, "DISABLED", "OPEN_OPERATOR_WORKSPACE");
  }

  @Transactional
  public DraftPreview preview(UUID reviewCaseId, String targetType, UUID actorId) {
    ExceptionCase reviewCase = caseRepository.findByIdAndTenantId(reviewCaseId, TenantContext.requireTenantId()).orElseThrow();
    DraftPreview preview = buildPreview(reviewCase, targetType == null || targetType.isBlank() ? "QUOTE" : targetType.toUpperCase());
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "DRAFT_PREVIEW_GENERATED", "Internal draft preview generated without external write", "{\"targetType\":\"" + escape(preview.targetType()) + "\",\"draftPreparationAllowed\":" + preview.draftPreparationAllowed() + "}");
    return preview;
  }

  private void ensureAllowed(ExceptionCase reviewCase, UUID actorId, String targetType) {
    ReviewReadiness readiness = readiness(reviewCase);
    if (!readiness.draftPreparationAllowed()) {
      block(targetType, reviewCase, actorId, readiness.blockingReasons());
    }
  }

  public List<BlockingReason> blockingReasons(ExceptionCase reviewCase) {
    return readiness(reviewCase).blockingReasons();
  }

  public ReviewReadiness readiness(ExceptionCase reviewCase) {
    if (reviewCase.getValidationRunId() == null) {
      List<BlockingReason> reasons = List.of(new BlockingReason("BOT_HANDOFF_NOT_VALIDATION_BACKED", "ERROR", "Bot-originated operator handoff is not validation-backed and cannot be prepared into a draft", "convert into a validation-backed RFQ workflow in a later phase"));
      return new ReviewReadiness("BOT_HANDOFF_NOT_DRAFT_READY", false, reasons, List.of(), List.of(), List.of(), List.of("handle in bot operator queue"));
    }
    boolean approvedForDraft = "APPROVED_FOR_DRAFT".equals(reviewCase.getStatus()) || "REVIEW_NOT_REQUIRED".equals(reviewCase.getStatus());
    UUID tenantId = TenantContext.requireTenantId();
    UUID runId = reviewCase.getValidationRunId();
    List<ValidationIssue> openIssues = issueRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, runId).stream()
        .filter(issue -> "OPEN".equals(issue.getStatus()))
        .toList();
    List<ApprovalRequirement> approvals = approvalRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, runId);
    List<ApprovalRequirement> openApprovals = approvals.stream()
        .filter(approval -> "OPEN".equals(approval.getStatus()))
        .toList();
    List<ApprovalRequirement> rejectedApprovals = approvals.stream()
        .filter(approval -> "REJECTED".equals(approval.getStatus()))
        .toList();
    List<ApprovalRequirement> resolvedApprovals = approvals.stream()
        .filter(approval -> RESOLVED_APPROVAL_STATUSES.contains(approval.getStatus()))
        .toList();
    List<BlockingReason> reasons = new ArrayList<>();

    List<SubstituteCandidate> candidates = substituteCandidateRepository.findByTenantIdAndValidationRunIdOrderByRankScoreDesc(tenantId, runId);
    if (candidates.stream().anyMatch(candidate -> statusContains(candidate.getStatus(), "BLOCKED"))) {
      reasons.add(new BlockingReason("BLOCKED_SUBSTITUTE", "ERROR", "Blocked substitute cannot be prepared into draft workflow", "reject blocked substitute or choose a safe candidate"));
    }
    openIssues.stream()
        .filter(issue -> HARD_BLOCKING_ISSUES.contains(issue.getIssueType()))
        .map(issue -> new BlockingReason(issue.getIssueType(), issue.getSeverity(), "Unresolved hard validation issue blocks draft preparation", suggestedCorrectionAction(issue.getIssueType())))
        .forEach(reasons::add);
    openIssues.stream()
        .filter(issue -> "CRITICAL".equals(issue.getSeverity()) && !APPROVAL_BACKED_ISSUES.contains(issue.getIssueType()))
        .map(issue -> new BlockingReason(issue.getIssueType(), issue.getSeverity(), "Critical validation issue blocks draft preparation", suggestedCorrectionAction(issue.getIssueType())))
        .forEach(reasons::add);
    candidates.stream()
        .filter(candidate -> "SELECTED".equals(candidate.getStatus()) && candidate.isRequiresApproval())
        .filter(candidate -> !lineApprovalResolved(approvals, candidate.getExtractedLineItemId(), "SUBSTITUTE_REQUIRES_APPROVAL"))
        .map(candidate -> new BlockingReason("SUBSTITUTE_REQUIRES_APPROVAL", "HIGH", "Selected risky substitute requires manager approval before draft preparation", "approve or reject the substitute approval requirement"))
        .forEach(reasons::add);
    openIssues.stream()
        .filter(issue -> APPROVAL_BACKED_ISSUES.contains(issue.getIssueType()))
        .filter(issue -> !issueApprovalResolved(approvals, issue))
        .map(issue -> new BlockingReason(issue.getIssueType(), issue.getSeverity(), "Approval-backed validation issue blocks draft preparation until manager approval is resolved", "approve or override with reason"))
        .forEach(reasons::add);
    openApprovals.stream()
        .filter(approval -> APPROVAL_BACKED_REQUIREMENTS.contains(approval.getRequirementType()))
        .filter(approval -> reasons.stream().noneMatch(reason -> reason.issueCode().equals(approval.getRequirementType())))
        .map(approval -> new BlockingReason(approval.getRequirementType(), approval.getSeverity(), "Manager approval is pending: " + approval.getRequirementType(), "approve or reject manager approval requirement"))
        .forEach(reasons::add);
    rejectedApprovals.stream()
        .filter(approval -> APPROVAL_BACKED_REQUIREMENTS.contains(approval.getRequirementType()))
        .map(approval -> new BlockingReason(approval.getRequirementType(), approval.getSeverity(), "Required approval was rejected: " + approval.getRequirementType(), "correct the underlying issue or start a new approval cycle"))
        .forEach(reasons::add);
    if (productMatchRepository.findByTenantIdAndValidationRunId(tenantId, runId).stream().anyMatch(match -> "AMBIGUOUS".equals(match.getStatus()) || "NOT_FOUND".equals(match.getStatus()))) {
      reasons.add(new BlockingReason("UNRESOLVED_PRODUCT_MATCH", "ERROR", "Unresolved product match blocks draft preparation", "map raw SKU to an existing product"));
    }
    if (reviewApprovalRequired(reviewCase, openIssues, approvals) && !approvedForDraft) {
      reasons.add(new BlockingReason("REVIEW_NOT_APPROVED", "ERROR", "Review case is not approved for draft preparation", "approve review case after resolving blocking issues"));
    }
    List<String> nextActions = reasons.stream()
        .map(BlockingReason::suggestedCorrectionAction)
        .filter(action -> action != null && !action.isBlank())
        .distinct()
        .toList();
    String readinessStatus = reasons.isEmpty() ? "READY" : openApprovals.isEmpty() && rejectedApprovals.isEmpty() ? "BLOCKED" : "PENDING_OR_REJECTED_APPROVAL";
    return new ReviewReadiness(readinessStatus, reasons.isEmpty(), reasons, approvalReviews(openApprovals), approvalReviews(rejectedApprovals), approvalReviews(resolvedApprovals), nextActions);
  }

  private DraftPreview buildPreview(ExceptionCase reviewCase, String targetType) {
    UUID tenantId = TenantContext.requireTenantId();
    ReviewReadiness readiness = readiness(reviewCase);
    if (reviewCase.getValidationRunId() == null) {
      return new DraftPreview(targetType, false, readiness.blockingReasons(), readiness, List.of(), BigDecimal.ZERO, null, true, true);
    }
    ValidationRun run = validationRunRepository.findByIdAndTenantId(reviewCase.getValidationRunId(), tenantId).orElseThrow();
    List<BlockingReason> blockers = readiness.blockingReasons();
    Map<UUID, ProductMatchResult> products = productMatchRepository.findByTenantIdAndValidationRunId(tenantId, run.getId()).stream().collect(Collectors.toMap(ProductMatchResult::getExtractedLineItemId, Function.identity(), (a, b) -> a));
    Map<UUID, PriceCheckResult> prices = priceRepository.findByTenantIdAndValidationRunId(tenantId, run.getId()).stream().collect(Collectors.toMap(PriceCheckResult::getExtractedLineItemId, Function.identity(), (a, b) -> a));
    Map<UUID, UomNormalizationResult> uoms = uomRepository.findByTenantIdAndValidationRunId(tenantId, run.getId()).stream().collect(Collectors.toMap(UomNormalizationResult::getExtractedLineItemId, Function.identity(), (a, b) -> a));
    Map<UUID, MarginCheckResult> margins = marginRepository.findByTenantIdAndValidationRunId(tenantId, run.getId()).stream().collect(Collectors.toMap(MarginCheckResult::getExtractedLineItemId, Function.identity(), (a, b) -> a));
    Map<UUID, DiscountCheckResult> discounts = discountRepository.findByTenantIdAndValidationRunId(tenantId, run.getId()).stream().filter(item -> item.getExtractedLineItemId() != null).collect(Collectors.toMap(DiscountCheckResult::getExtractedLineItemId, Function.identity(), (a, b) -> a));
    Map<UUID, InventoryCheckResult> inventory = inventoryRepository.findByTenantIdAndValidationRunId(tenantId, run.getId()).stream().collect(Collectors.toMap(InventoryCheckResult::getExtractedLineItemId, Function.identity(), (a, b) -> a));
    Map<UUID, SubstituteCandidate> selectedSubstitutes = substituteCandidateRepository.findByTenantIdAndValidationRunIdOrderByRankScoreDesc(tenantId, run.getId()).stream().filter(candidate -> "SELECTED".equals(candidate.getStatus())).collect(Collectors.toMap(SubstituteCandidate::getExtractedLineItemId, Function.identity(), (a, b) -> a));
    List<DraftPreviewLine> lines = new ArrayList<>();
    BigDecimal subtotal = BigDecimal.ZERO;
    String currency = null;
    for (ExtractedLineItem line : lineRepository.findByTenantIdAndExtractionResultId(tenantId, run.getExtractionResultId())) {
      ProductMatchResult match = products.get(line.getId());
      SubstituteCandidate substitute = selectedSubstitutes.get(line.getId());
      PriceCheckResult price = prices.get(line.getId());
      UomNormalizationResult uom = uoms.get(line.getId());
      MarginCheckResult margin = margins.get(line.getId());
      DiscountCheckResult discount = discounts.get(line.getId());
      InventoryCheckResult stock = inventory.get(line.getId());
      Product product = match == null || match.getMatchedProductId() == null ? null : productRepository.findByIdAndTenantIdAndDeletedAtIsNull(match.getMatchedProductId(), tenantId).orElse(null);
      Product substituteProduct = substitute == null ? null : productRepository.findByIdAndTenantIdAndDeletedAtIsNull(substitute.getSubstituteProductId(), tenantId).orElse(null);
      BigDecimal quantity = line.getNormalizedQuantity() == null ? BigDecimal.ONE : line.getNormalizedQuantity();
      if (price != null && price.getUnitPrice() != null) {
        subtotal = subtotal.add(price.getUnitPrice().multiply(quantity));
        if (currency == null) currency = price.getCurrency();
      }
      lines.add(new DraftPreviewLine(line.getId(), line.getLineNumber(), line.getRawSku(), line.getRawDescription(), quantity, uom == null || uom.getNormalizedUom() == null ? line.getNormalizedUom() : uom.getNormalizedUom(), product == null ? null : product.getId(), product == null ? null : product.getSku(), product == null ? null : product.getName(), substituteProduct == null ? null : substituteProduct.getId(), substituteProduct == null ? null : substituteProduct.getSku(), substituteProduct == null ? null : substituteProduct.getName(), price == null ? null : price.getUnitPrice(), price == null ? null : price.getCurrency(), margin == null ? null : margin.getGrossMarginPercent(), discount == null ? null : discount.getRequestedDiscountPercent(), stock == null ? "NOT_CHECKED" : stock.getStatus(), price == null ? "NOT_CHECKED" : price.getStatus(), margin == null ? "NOT_CHECKED" : margin.getStatus(), lineValidationStatus(match, price, stock)));
    }
    return new DraftPreview(targetType, readiness.draftPreparationAllowed(), blockers, readiness, lines, subtotal, currency, true, true);
  }

  private String lineValidationStatus(ProductMatchResult match, PriceCheckResult price, InventoryCheckResult stock) {
    if (match == null || !"MATCHED".equals(match.getStatus())) return "NEEDS_PRODUCT_REVIEW";
    if (price == null || !"PRICE_FOUND".equals(price.getStatus())) return "NEEDS_PRICE_REVIEW";
    if (stock != null && !"AVAILABLE".equals(stock.getStatus())) return "NEEDS_STOCK_REVIEW";
    return "VALIDATED";
  }

  private void block(String targetType, ExceptionCase reviewCase, UUID actorId, List<BlockingReason> reasons) {
    String reason = reasons.isEmpty() ? "Draft preparation is blocked" : reasons.get(0).reason();
    actionService.record(actorId, "REVIEW_CASE", reviewCase.getId(), "DRAFT_PREPARATION_BLOCKED", "Draft preparation blocked by validation/review gates", "{\"targetType\":\"" + escape(targetType) + "\",\"reason\":\"" + escape(reason) + "\"}");
    auditEventService.record("DRAFT_PREPARATION_BLOCKED", targetType, reviewCase.getId().toString(), actorId, "{\"reason\":\"" + escape(reason) + "\",\"validationRunId\":\"" + reviewCase.getValidationRunId() + "\"}");
    throw new DraftPreparationBlockedException(reasons);
  }

  private String suggestedCorrectionAction(String issueType) {
    return switch (issueType) {
      case "INVALID_UOM" -> "correct UOM";
      case "INVALID_QUANTITY" -> "correct quantity";
      case "PRODUCT_AMBIGUOUS", "PRODUCT_NOT_FOUND" -> "map raw SKU to an existing product";
      default -> "resolve or override issue with reason";
    };
  }

  private List<ApprovalRequirementReview> approvalReviews(List<ApprovalRequirement> approvals) {
    return approvals.stream()
        .map(approval -> new ApprovalRequirementReview(approval.getId(), approval.getExtractedLineItemId(), approval.getRequirementType(), approval.getSeverity(), approval.getStatus(), approval.getReason(), approval.getCreatedAt()))
        .toList();
  }

  private boolean issueApprovalResolved(List<ApprovalRequirement> approvals, ValidationIssue issue) {
    return approvals.stream()
        .filter(approval -> approvalMatchesIssue(approval, issue))
        .anyMatch(approval -> RESOLVED_APPROVAL_STATUSES.contains(approval.getStatus()));
  }

  private boolean approvalMatchesIssue(ApprovalRequirement approval, ValidationIssue issue) {
    boolean sameLine = approval.getExtractedLineItemId() == null || issue.getExtractedLineItemId() == null || approval.getExtractedLineItemId().equals(issue.getExtractedLineItemId());
    return sameLine && switch (issue.getIssueType()) {
      case "LOW_EXTRACTION_CONFIDENCE" -> "NEEDS_HUMAN_REVIEW".equals(approval.getRequirementType());
      case "MARGIN_BELOW_GUARDRAIL" -> "MARGIN_BELOW_GUARDRAIL".equals(approval.getRequirementType());
      case "DISCOUNT_REQUIRES_APPROVAL" -> "DISCOUNT_REQUIRES_APPROVAL".equals(approval.getRequirementType());
      case "SUBSTITUTE_REQUIRES_APPROVAL" -> "SUBSTITUTE_REQUIRES_APPROVAL".equals(approval.getRequirementType());
      default -> issue.getIssueType().equals(approval.getRequirementType());
    };
  }

  private boolean lineApprovalResolved(List<ApprovalRequirement> approvals, UUID lineItemId, String requirementType) {
    return approvals.stream()
        .filter(approval -> requirementType.equals(approval.getRequirementType()))
        .filter(approval -> lineItemId.equals(approval.getExtractedLineItemId()))
        .anyMatch(approval -> RESOLVED_APPROVAL_STATUSES.contains(approval.getStatus()));
  }

  private boolean statusContains(String status, String marker) {
    return status != null && status.contains(marker);
  }

  private boolean reviewApprovalRequired(ExceptionCase reviewCase, List<ValidationIssue> openIssues, List<ApprovalRequirement> approvals) {
    if ("NEEDS_REVIEW_AFTER_CORRECTION".equals(reviewCase.getStatus()) || "WAITING_APPROVAL".equals(reviewCase.getStatus()) || "REVIEW_REQUIRED".equals(reviewCase.getStatus())) {
      return true;
    }
    return openIssues.stream().anyMatch(issue -> "OPEN".equals(issue.getStatus()))
        || approvals.stream().anyMatch(approval -> "OPEN".equals(approval.getStatus()) || "REJECTED".equals(approval.getStatus()));
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
