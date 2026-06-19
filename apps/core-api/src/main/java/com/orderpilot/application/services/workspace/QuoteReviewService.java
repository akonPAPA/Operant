package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage12ADtos.*;
import com.orderpilot.api.dto.Stage12CDtos.*;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.ProductSubstitutionService;
import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.common.tenant.TenantContextMissingException;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.workspace.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteReviewService {
  private static final Set<String> TERMINAL_STATUSES = Set.of("APPROVED", "REJECTED", "CHANGES_REQUESTED", "EXPIRED", "CONVERTED_TO_INTERNAL_ORDER");
  private static final Set<String> SUBSTITUTE_ISSUES = Set.of("INSUFFICIENT_STOCK", "PRODUCT_OUT_OF_STOCK_SUBSTITUTE_AVAILABLE", "SUBSTITUTE_REQUIRES_APPROVAL", "SUBSTITUTE_BLOCKED_FOR_CUSTOMER", "NO_SAFE_SUBSTITUTE_FOUND");
  private static final Set<String> CUSTOMER_ISSUES = Set.of("CUSTOMER_NOT_RESOLVED", "CUSTOMER_UNRESOLVED");
  private static final Set<String> PRODUCT_ISSUES = Set.of("PRODUCT_NOT_RESOLVED", "PRICE_NOT_RESOLVED");
  private static final Set<String> QUANTITY_ISSUES = Set.of("INVALID_QUANTITY", "INSUFFICIENT_STOCK");
  private static final Set<String> UOM_ISSUES = Set.of("UOM_UNRECOGNIZED", "INVALID_UOM", "PRICE_NOT_RESOLVED");

  private final DraftQuoteRepository quoteRepository;
  private final DraftQuoteLineRepository lineRepository;
  private final QuoteValidationIssueRepository issueRepository;
  private final QuoteApprovalRequestRepository approvalRepository;
  private final QuoteConversionAttemptRepository attemptRepository;
  private final QuoteSourceLinkRepository sourceLinkRepository;
  private final CustomerAccountRepository customerRepository;
  private final ProductRepository productRepository;
  private final PricingService pricingService;
  private final QuoteInventoryValidationService inventoryService;
  private final QuoteMarginValidationService marginService;
  private final ProductSubstitutionService substitutionService;
  private final QuoteLifecycleService lifecycleService;
  private final ChannelToQuoteWiringService channelToQuoteWiringService;
  private final AuditEventService auditEventService;
  private final AuditEventRepository auditEventRepository;
  private final LocationRepository locationRepository;
  private final ChangeRequestService changeRequestService;
  private final Clock clock;

  public QuoteReviewService(DraftQuoteRepository quoteRepository, DraftQuoteLineRepository lineRepository, QuoteValidationIssueRepository issueRepository, QuoteApprovalRequestRepository approvalRepository, QuoteConversionAttemptRepository attemptRepository, QuoteSourceLinkRepository sourceLinkRepository, CustomerAccountRepository customerRepository, ProductRepository productRepository, PricingService pricingService, QuoteInventoryValidationService inventoryService, QuoteMarginValidationService marginService, ProductSubstitutionService substitutionService, QuoteLifecycleService lifecycleService, ChannelToQuoteWiringService channelToQuoteWiringService, AuditEventService auditEventService, AuditEventRepository auditEventRepository, LocationRepository locationRepository, ChangeRequestService changeRequestService, Clock clock) {
    this.quoteRepository = quoteRepository;
    this.lineRepository = lineRepository;
    this.issueRepository = issueRepository;
    this.approvalRepository = approvalRepository;
    this.attemptRepository = attemptRepository;
    this.sourceLinkRepository = sourceLinkRepository;
    this.customerRepository = customerRepository;
    this.productRepository = productRepository;
    this.pricingService = pricingService;
    this.inventoryService = inventoryService;
    this.marginService = marginService;
    this.substitutionService = substitutionService;
    this.lifecycleService = lifecycleService;
    this.channelToQuoteWiringService = channelToQuoteWiringService;
    this.auditEventService = auditEventService;
    this.auditEventRepository = auditEventRepository;
    this.locationRepository = locationRepository;
    this.changeRequestService = changeRequestService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<QuoteReviewQueueRow> queue(String status, String sourceType, String channel, String customer, String issueType, String severity, Boolean reviewRequired, UUID assignedTo, Instant createdFrom, Instant createdTo) {
    UUID tenantId = TenantContext.requireTenantId();
    return quoteRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
        .filter(quote -> status == null || status.equals(quote.getStatus()))
        .filter(quote -> sourceType == null || sourceType.equals(quote.getSourceType()))
        .filter(quote -> reviewRequired == null || reviewRequired == quote.isRequiresHumanReview())
        .filter(quote -> customer == null || contains(quote.getCustomerDisplayName(), customer) || (quote.getCustomerAccountId() != null && quote.getCustomerAccountId().toString().equals(customer)))
        .filter(quote -> createdFrom == null || !quote.getCreatedAt().isBefore(createdFrom))
        .filter(quote -> createdTo == null || !quote.getCreatedAt().isAfter(createdTo))
        .map(quote -> queueRow(tenantId, quote))
        .filter(row -> channel == null || channel.equals(row.sourceChannel()))
        .filter(row -> issueType == null || quoteHasIssue(tenantId, row.quoteId(), issueType, null))
        .filter(row -> severity == null || quoteHasIssue(tenantId, row.quoteId(), null, severity))
        .filter(row -> assignedTo == null)
        .toList();
  }

  @Transactional(readOnly = true)
  public QuoteReviewDetail detail(UUID quoteId) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftQuote quote = quote(tenantId, quoteId);
    List<DraftQuoteLine> lines = lineRepository.findByTenantIdAndDraftQuoteId(tenantId, quoteId);
    List<QuoteValidationIssue> issues = issues(tenantId, quoteId);
    List<QuoteApprovalRequest> approvals = approvalRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(tenantId, quoteId);
    QuoteConversionAttempt attempt = attemptRepository.findFirstByTenantIdAndQuoteIdOrderByCreatedAtDesc(tenantId, quoteId).orElse(null);
    QuoteSourceContextSnapshot sourceContext = sourceLinkRepository.findFirstByTenantIdAndQuoteId(tenantId, quoteId).isPresent()
        ? channelToQuoteWiringService.sourceContextSnapshot(quoteId)
        : null;
    return new QuoteReviewDetail(
        QuoteHeader.from(quote),
        quote.getStatus(),
        safeSourceContext(sourceContext),
        ConversionAttemptSummary.from(attempt),
        safeCandidateLines(sourceContext),
        lines.stream().map(QuoteLine::from).toList(),
        issues.stream().map(ValidationIssue::from).toList(),
        substituteCandidates(tenantId, quote, lines),
        pricingSummary(quote, issues, approvals),
        approvals.stream().map(ApprovalRequest::from).toList(),
        auditEventRepository.findByTenantIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(tenantId, "DRAFT_QUOTE", quoteId.toString()).stream().map(AuditTimelineEvent::from).toList(),
        reviewReasons(issues, approvals, lines));
  }

  @Transactional(readOnly = true)
  public List<ConversionAttemptSummary> conversionAttempts(String status) {
    UUID tenantId = TenantContext.requireTenantId();
    List<QuoteConversionAttempt> attempts = status == null ? attemptRepository.findByTenantIdOrderByCreatedAtDesc(tenantId) : attemptRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status);
    return attempts.stream().map(ConversionAttemptSummary::from).toList();
  }

  @Transactional(readOnly = true)
  public List<ValidationIssue> issues(String issueType, String severity, UUID quoteId) {
    UUID tenantId = TenantContext.requireTenantId();
    return issueRepository.findByTenantIdOrderByCreatedAtAsc(tenantId).stream()
        .filter(issue -> quoteId == null || issue.getDraftQuoteId().equals(quoteId))
        .filter(issue -> issueType == null || issueType.equals(issue.getIssueCode()))
        .filter(issue -> severity == null || severity.equals(issue.getSeverity()))
        .map(ValidationIssue::from)
        .toList();
  }

  @Transactional
  public QuoteReviewCommandResult resolveIssue(UUID quoteId, UUID issueId, ResolveValidationIssueCommand command) {
    CommandContext ctx = context(command == null ? null : command.tenantId(), command == null ? null : command.actorId());
    DraftQuote quote = quote(ctx.tenantId(), quoteId);
    requireCorrectableLifecycle(quote);
    QuoteValidationIssue issue = issue(ctx.tenantId(), quoteId, issueId);
    String previousStatus = quote.getStatus();
    String previousIssueStatus = issue.getStatus();
    issue.resolve(clock.instant());
    issueRepository.save(issue);
    DraftQuote next = lifecycleService.recalculate(quote);
    audit("VALIDATION_ISSUE_RESOLVED", next, ctx.actorId(), issue.getDraftQuoteLineId(), issueId, previousIssueStatus, "RESOLVED", command == null ? null : command.reasonCode(), command == null ? null : command.note());
    return result(ctx.tenantId(), previousStatus, next, "VALIDATION_ISSUE_RESOLVED");
  }

  @Transactional
  public QuoteReviewCommandResult rejectIssueSuggestion(UUID quoteId, UUID issueId, RejectValidationIssueSuggestionCommand command) {
    CommandContext ctx = context(command == null ? null : command.tenantId(), command == null ? null : command.actorId());
    DraftQuote quote = quote(ctx.tenantId(), quoteId);
    requireCorrectableLifecycle(quote);
    QuoteValidationIssue issue = issue(ctx.tenantId(), quoteId, issueId);
    String previousStatus = quote.getStatus();
    issue.reject(clock.instant());
    issueRepository.save(issue);
    DraftQuote next = lifecycleService.recalculate(quote);
    audit("VALIDATION_ISSUE_REJECTED", next, ctx.actorId(), issue.getDraftQuoteLineId(), issueId, "OPEN", "REJECTED", command == null ? null : command.reasonCode(), command == null ? null : command.note());
    return result(ctx.tenantId(), previousStatus, next, "VALIDATION_ISSUE_REJECTED");
  }

  @Transactional
  public QuoteReviewCommandResult escalateIssue(UUID quoteId, UUID issueId, EscalateValidationIssueCommand command) {
    CommandContext ctx = context(command == null ? null : command.tenantId(), command == null ? null : command.actorId());
    DraftQuote quote = quote(ctx.tenantId(), quoteId);
    requireCorrectableLifecycle(quote);
    QuoteValidationIssue issue = issue(ctx.tenantId(), quoteId, issueId);
    String previousStatus = quote.getStatus();
    issue.escalate(clock.instant());
    issueRepository.save(issue);
    approvalRepository.save(new QuoteApprovalRequest(ctx.tenantId(), quoteId, issue.getDraftQuoteLineId(), "QUOTE_REVIEW_ESCALATION", issue.getSeverity(), firstNonBlank(command == null ? null : command.reasonCode(), issue.getIssueCode()), issue.getMessage(), clock.instant()));
    quote.transition("PENDING_APPROVAL", "NEEDS_REVIEW", true, ctx.actorId(), clock.instant());
    quote = quoteRepository.save(quote);
    audit("VALIDATION_ISSUE_ESCALATED", quote, ctx.actorId(), issue.getDraftQuoteLineId(), issueId, "OPEN", "ESCALATED", command == null ? null : command.reasonCode(), command == null ? null : command.note());
    audit("QUOTE_REVIEW_READY_FOR_APPROVAL", quote, ctx.actorId(), issue.getDraftQuoteLineId(), issueId, previousStatus, quote.getStatus(), "QUOTE_REVIEW_ESCALATION", command == null ? null : command.note());
    return result(ctx.tenantId(), previousStatus, quote, "VALIDATION_ISSUE_ESCALATED");
  }

  @Transactional
  public QuoteReviewCommandResult applyIssueFix(UUID quoteId, UUID issueId, ApplyValidationIssueFixCommand command) {
    if (command == null || command.fixType() == null) throw new QuoteLifecycleViolation("fixType is required");
    return switch (command.fixType()) {
      case "RESOLVE" -> resolveIssue(quoteId, issueId, new ResolveValidationIssueCommand(command.tenantId(), command.actorId(), command.actorRole(), command.reasonCode(), command.note()));
      case "ESCALATE" -> escalateIssue(quoteId, issueId, new EscalateValidationIssueCommand(command.tenantId(), command.actorId(), command.actorRole(), command.reasonCode(), command.note()));
      default -> throw new QuoteLifecycleViolation("Unsupported validation issue fix type: " + command.fixType());
    };
  }

  @Transactional
  public QuoteReviewCommandResult correctCustomer(UUID quoteId, CorrectQuoteCustomerCommand command) {
    CommandContext ctx = context(command == null ? null : command.tenantId(), command == null ? null : command.actorId());
    DraftQuote quote = quote(ctx.tenantId(), quoteId);
    requireCorrectableLifecycle(quote);
    if (command == null || command.customerAccountId() == null) throw new QuoteLifecycleViolation("customerAccountId is required");
    CustomerAccount customer = customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(command.customerAccountId(), ctx.tenantId()).orElseThrow(() -> new NotFoundException("Customer account not found: " + command.customerAccountId()));
    String previousStatus = quote.getStatus();
    String previousValue = quote.getCustomerAccountId() == null ? "" : quote.getCustomerAccountId().toString();
    quote.correctCustomer(customer.getId(), customer.getDisplayName(), clock.instant());
    resolveOpenIssues(ctx.tenantId(), quoteId, null, CUSTOMER_ISSUES);
    quote = lifecycleService.recalculate(quote);
    audit("QUOTE_CUSTOMER_CORRECTED", quote, ctx.actorId(), null, null, previousValue, customer.getId().toString(), command.reasonCode(), command.note());
    revalidated(quote, ctx.actorId());
    return result(ctx.tenantId(), previousStatus, quote, "QUOTE_CUSTOMER_CORRECTED");
  }

  @Transactional
  public QuoteReviewCommandResult correctLine(UUID quoteId, UUID lineId, CorrectQuoteLineCommand command) {
    CommandContext ctx = context(command == null ? null : command.tenantId(), command == null ? null : command.actorId());
    DraftQuote quote = quote(ctx.tenantId(), quoteId);
    requireCorrectableLifecycle(quote);
    DraftQuoteLine line = line(ctx.tenantId(), quoteId, lineId);
    String previousStatus = quote.getStatus();
    List<String> previousValues = new ArrayList<>();
    if (command != null && command.removeLine()) {
      previousValues.add("status=" + line.getStatus());
      line.removeFromReview(ctx.actorId(), command.note(), clock.instant());
      lineRepository.save(line);
      resolveOpenIssues(ctx.tenantId(), quoteId, lineId, Set.of());
    } else if (command != null && command.manualFollowUp()) {
      previousValues.add("validationStatus=" + line.getValidationStatus());
      line.markManualFollowUp(firstNonBlank(command.reasonCode(), "MANUAL_FOLLOW_UP"), ctx.actorId(), command.note(), clock.instant());
      lineRepository.save(line);
    } else {
      if (command == null) throw new QuoteLifecycleViolation("line correction command is required");
      if (command.quantity() != null) {
        if (command.quantity().compareTo(BigDecimal.ZERO) <= 0) throw new QuoteLifecycleViolation("Quantity must be greater than zero");
        previousValues.add("quantity=" + line.getQuantity());
        line.correctQuantity(command.quantity(), clock.instant());
        resolveOpenIssues(ctx.tenantId(), quoteId, lineId, QUANTITY_ISSUES);
      }
      if (command.uom() != null && !command.uom().isBlank()) {
        String normalized = normalizeUom(command.uom());
        if ("UNKNOWN".equals(normalized)) throw new QuoteLifecycleViolation("UOM is not supported for quote correction");
        previousValues.add("uom=" + line.getUom());
        line.correctUom(normalized, clock.instant());
        resolveOpenIssues(ctx.tenantId(), quoteId, lineId, UOM_ISSUES);
      }
      if (command.productId() != null) {
        Product product = productRepository.findByIdAndTenantIdAndDeletedAtIsNull(command.productId(), ctx.tenantId()).orElseThrow(() -> new NotFoundException("Product not found: " + command.productId()));
        previousValues.add("productId=" + line.getProductId());
        line.correctProduct(product.getId(), product.getName(), product.getSku(), clock.instant());
        resolveOpenIssues(ctx.tenantId(), quoteId, lineId, PRODUCT_ISSUES);
      }
      lineRepository.save(line);
      revalidateLine(ctx.tenantId(), quote, line);
    }
    quote = lifecycleService.recalculate(quote);
    audit("QUOTE_LINE_CORRECTED", quote, ctx.actorId(), lineId, null, String.join(";", previousValues), "REVALIDATED", command == null ? null : command.reasonCode(), command == null ? null : command.note());
    revalidated(quote, ctx.actorId());
    return result(ctx.tenantId(), previousStatus, quote, "QUOTE_LINE_CORRECTED");
  }

  @Transactional
  public QuoteReviewCommandResult selectSubstitute(UUID quoteId, UUID lineId, QuoteLineSubstituteCommand command) {
    CommandContext ctx = context(command == null ? null : command.tenantId(), command == null ? null : command.actorId());
    DraftQuote quote = quote(ctx.tenantId(), quoteId);
    requireCorrectableLifecycle(quote);
    DraftQuoteLine line = line(ctx.tenantId(), quoteId, lineId);
    if (command == null || command.substituteProductId() == null) throw new QuoteLifecycleViolation("substituteProductId is required");
    ProductSubstitutionService.SubstituteCandidate candidate = substituteCandidate(ctx.tenantId(), quote, line, command.substituteProductId());
    String previousStatus = quote.getStatus();
    String previousValue = line.getSelectedSubstituteProductId() == null ? "" : line.getSelectedSubstituteProductId().toString();
    if (candidate.blocked()) {
      audit("QUOTE_SUBSTITUTE_REJECTED", quote, ctx.actorId(), lineId, null, previousValue, command.substituteProductId().toString(), "SUBSTITUTE_BLOCKED_FOR_CUSTOMER", command.note());
      throw new QuoteLifecycleViolation("Blocked substitute cannot be selected");
    }
    if (candidate.requiresApproval() || "HIGH".equalsIgnoreCase(candidate.riskLevel().name())) {
      line.setSubstituteDecision("SUBSTITUTE_APPROVAL_REQUIRED", candidate.productId(), candidate.reasonCode(), ctx.actorId(), command.note(), clock.instant());
      lineRepository.save(line);
      approvalRepository.save(new QuoteApprovalRequest(ctx.tenantId(), quoteId, lineId, "SUBSTITUTE_POLICY", "WARNING", "SUBSTITUTE_REQUIRES_APPROVAL", "High-risk substitute requires approval", clock.instant()));
      quote.transition("PENDING_APPROVAL", "NEEDS_REVIEW", true, ctx.actorId(), clock.instant());
      quote = quoteRepository.save(quote);
      audit("QUOTE_SUBSTITUTE_SELECTED", quote, ctx.actorId(), lineId, null, previousValue, command.substituteProductId().toString(), "SUBSTITUTE_REQUIRES_APPROVAL", command.note());
      audit("QUOTE_REVIEW_READY_FOR_APPROVAL", quote, ctx.actorId(), lineId, null, previousStatus, quote.getStatus(), "SUBSTITUTE_REQUIRES_APPROVAL", command.note());
      return result(ctx.tenantId(), previousStatus, quote, "QUOTE_SUBSTITUTE_SELECTED");
    }
    line.setSubstituteDecision("SUBSTITUTE_APPROVED", candidate.productId(), candidate.reasonCode(), ctx.actorId(), command.note(), clock.instant());
    lineRepository.save(line);
    resolveOpenIssues(ctx.tenantId(), quoteId, lineId, SUBSTITUTE_ISSUES);
    quote = lifecycleService.recalculate(quote);
    audit("QUOTE_SUBSTITUTE_SELECTED", quote, ctx.actorId(), lineId, null, previousValue, command.substituteProductId().toString(), command.reasonCode(), command.note());
    revalidated(quote, ctx.actorId());
    return result(ctx.tenantId(), previousStatus, quote, "QUOTE_SUBSTITUTE_SELECTED");
  }

  @Transactional
  public QuoteReviewCommandResult rejectSubstitute(UUID quoteId, UUID lineId, QuoteLineSubstituteCommand command) {
    CommandContext ctx = context(command == null ? null : command.tenantId(), command == null ? null : command.actorId());
    DraftQuote quote = quote(ctx.tenantId(), quoteId);
    requireCorrectableLifecycle(quote);
    DraftQuoteLine line = line(ctx.tenantId(), quoteId, lineId);
    if (command == null || command.substituteProductId() == null) throw new QuoteLifecycleViolation("substituteProductId is required");
    ProductSubstitutionService.SubstituteCandidate candidate = substituteCandidate(ctx.tenantId(), quote, line, command.substituteProductId());
    String previousStatus = quote.getStatus();
    String previousValue = line.getSubstituteDecisionStatus();
    line.setSubstituteDecision("SUBSTITUTE_REJECTED", null, firstNonBlank(command.reasonCode(), "OPERATOR_REJECTED_SUBSTITUTE"), ctx.actorId(), command.note(), clock.instant());
    lineRepository.save(line);
    issueRepository.save(new QuoteValidationIssue(ctx.tenantId(), quoteId, lineId, "NO_SAFE_SUBSTITUTE_FOUND", "ERROR", true, "Operator rejected substitute candidate", "{\"rejectedSubstituteProductId\":\"" + command.substituteProductId() + "\"}", clock.instant()));
    quote = lifecycleService.recalculate(quote);
    audit("QUOTE_SUBSTITUTE_REJECTED", quote, ctx.actorId(), lineId, null, previousValue, candidate.productId().toString(), command.reasonCode(), command.note());
    return result(ctx.tenantId(), previousStatus, quote, "QUOTE_SUBSTITUTE_REJECTED");
  }

  // OP-CAP-36: Quote Draft Assembly. Assemble an operator-safe draft quote
  // candidate from the reviewed/validated quote. The DraftQuote already exists
  // (it is the entity under review); assembly is the explicit operator step that
  // gates on review readiness, recalculates backend-owned status/risk/approval,
  // and returns a safe summary. Backend owns every calculated field — the client
  // supplies business intent (reasonCode/note) only.
  @Transactional
  public QuoteDraftSummary assembleDraft(UUID quoteId, AssembleQuoteDraftCommand command) {
    CommandContext ctx = context(command == null ? null : command.tenantId(), command == null ? null : command.actorId());
    DraftQuote quote = quoteRepository.findWithLockByIdAndTenantId(quoteId, ctx.tenantId())
        .orElseThrow(() -> new NotFoundException("Draft quote not found: " + quoteId));
    requireCorrectableLifecycle(quote);
    // Deterministic readiness gate: throws QuoteLifecycleViolation (409) when
    // unresolved blocking issues, pending substitutes, or rejected/blocked
    // substitutes remain. Frontend cannot assert resolution — backend derives it.
    lifecycleService.requireReadyForApproval(ctx.tenantId(), quote);
    String previousStatus = quote.getStatus();
    List<QuoteValidationIssue> issues = issues(ctx.tenantId(), quoteId);
    List<QuoteApprovalRequest> approvals = approvalRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(ctx.tenantId(), quoteId);
    List<DraftQuoteLine> lines = lineRepository.findByTenantIdAndDraftQuoteId(ctx.tenantId(), quoteId);
    boolean approvalRequired = approvals.stream().anyMatch(approval -> "OPEN".equals(approval.getStatus()));
    String newStatus = approvalRequired ? "PENDING_APPROVAL" : "DRAFT_ASSEMBLED";
    quote.transition(newStatus, "VALIDATED", approvalRequired, ctx.actorId(), clock.instant());
    quote = quoteRepository.save(quote);
    audit("QUOTE_DRAFT_ASSEMBLED", quote, ctx.actorId(), null, null, previousStatus, newStatus, command == null ? null : command.reasonCode(), command == null ? null : command.note());
    // OP-CAP-37: once (and only once) the quote is genuinely assembled (no approval
    // pending), prepare a tenant-scoped, non-executed external-sync ChangeRequest
    // candidate. When approval is still required the candidate is premature, so none
    // is prepared this slice. externalExecution stays DISABLED; no connector is called.
    String candidateStatus = "PENDING_INTERNAL_APPROVAL";
    if (!approvalRequired) {
      changeRequestService.prepareQuoteExternalSyncCandidate(quote.getId(), assembledCandidateSnapshot(quote, lines, issues, approvals, command), ctx.actorId());
      candidateStatus = "PREPARED";
    }
    return draftSummary(quote, lines, issues, approvals, approvalRequired, candidateStatus);
  }

  // Internal canonical snapshot stored on the candidate for later review. Server-built
  // and operator-safe: no client-supplied authority, no credentials, no raw audit/source
  // IDs beyond the quote workflow handle already used across the Quote Review contract.
  private String assembledCandidateSnapshot(DraftQuote quote, List<DraftQuoteLine> lines, List<QuoteValidationIssue> issues, List<QuoteApprovalRequest> approvals, AssembleQuoteDraftCommand command) {
    int lineCount = (int) lines.stream().filter(line -> !"REMOVED".equals(line.getStatus())).count();
    return "{\"quoteId\":\"" + quote.getId() + "\",\"quoteNumber\":\"" + escape(quote.getQuoteNumber())
        + "\",\"sourceWorkflowType\":\"QUOTE_REVIEW\",\"assembledStatus\":\"" + quote.getStatus()
        + "\",\"currency\":\"" + escape(quote.getCurrency()) + "\",\"subtotalAmount\":\"" + quote.getSubtotalAmount()
        + "\",\"discountAmount\":\"" + quote.getDiscountAmount() + "\",\"totalAmount\":\"" + quote.getTotalAmount()
        + "\",\"marginPercent\":\"" + quote.getMarginPercent() + "\",\"lineCount\":" + lineCount
        + ",\"approvalRequired\":false,\"validationSummary\":\"" + escape(validationSummary(issues, approvals))
        + "\",\"reasonCode\":\"" + escape(command == null ? null : command.reasonCode()) + "\",\"note\":\"" + escape(command == null ? null : command.note())
        + "\",\"externalExecution\":\"DISABLED\"}";
  }

  private QuoteDraftSummary draftSummary(DraftQuote quote, List<DraftQuoteLine> lines, List<QuoteValidationIssue> issues, List<QuoteApprovalRequest> approvals, boolean approvalRequired, String externalSyncCandidateStatus) {
    int lineCount = (int) lines.stream().filter(line -> !"REMOVED".equals(line.getStatus())).count();
    int blockingCount = (int) issues.stream().filter(issue -> "OPEN".equals(issue.getStatus()) && issue.isBlocking()).count();
    int warningCount = (int) issues.stream().filter(issue -> "OPEN".equals(issue.getStatus()) && !issue.isBlocking()).count();
    int stockWarningCount = (int) issues.stream().filter(issue -> "OPEN".equals(issue.getStatus()) && issue.getIssueCode() != null && issue.getIssueCode().contains("STOCK")).count();
    boolean marginApprovalOpen = approvals.stream().anyMatch(approval -> "OPEN".equals(approval.getStatus()) && approval.getReasonCode() != null && approval.getReasonCode().contains("MARGIN"));
    String marginStatus = marginApprovalOpen ? "APPROVAL_REQUIRED" : "OK";
    String riskLevel = approvalRequired ? "HIGH" : warningCount > 0 ? "MEDIUM" : "LOW";
    String nextAction = approvalRequired ? "APPROVAL_DECISION_REQUIRED" : "READY_FOR_INTERNAL_APPROVAL";
    String operatorMessage = approvalRequired
        ? "Draft quote assembled. Approval is required before any internal order conversion."
        : "Draft quote assembled and ready for the internal approval step. No external ERP/1C write is executed.";
    return new QuoteDraftSummary(
        quote.getId(),
        quote.getQuoteNumber(),
        quote.getStatus(),
        new CustomerSummary(quote.getCustomerAccountId(), quote.getCustomerDisplayName(), quote.getCustomerAccountId() == null ? "UNRESOLVED" : "RESOLVED"),
        quote.getCurrency(),
        quote.getSubtotalAmount(),
        quote.getDiscountAmount(),
        quote.getTotalAmount(),
        quote.getMarginPercent(),
        lineCount,
        blockingCount,
        warningCount,
        stockWarningCount,
        approvalRequired,
        riskLevel,
        marginStatus,
        validationSummary(issues, approvals),
        nextAction,
        operatorMessage,
        "DISABLED",
        quote.getUpdatedAt(),
        externalSyncCandidateStatus);
  }

  private QuoteReviewQueueRow queueRow(UUID tenantId, DraftQuote quote) {
    List<DraftQuoteLine> lines = lineRepository.findByTenantIdAndDraftQuoteId(tenantId, quote.getId());
    List<QuoteValidationIssue> issues = issues(tenantId, quote.getId());
    QuoteConversionAttempt attempt = attemptRepository.findFirstByTenantIdAndQuoteIdOrderByCreatedAtDesc(tenantId, quote.getId()).orElse(null);
    QuoteSourceLink source = sourceLinkRepository.findFirstByTenantIdAndQuoteId(tenantId, quote.getId()).orElse(null);
    return new QuoteReviewQueueRow(
        quote.getId(),
        source == null ? quote.getSourceType() : source.getSourceType(),
        source == null ? null : source.getSourceChannel(),
        new CustomerSummary(quote.getCustomerAccountId(), quote.getCustomerDisplayName(), quote.getCustomerAccountId() == null ? "UNRESOLVED" : "RESOLVED"),
        lines.size(),
        (int) issues.stream().filter(issue -> "OPEN".equals(issue.getStatus())).count(),
        highestSeverity(issues),
        quote.getStatus(),
        quote.getCreatedAt(),
        nextAction(quote, issues));
  }

  private QuoteReviewSourceContext safeSourceContext(QuoteSourceContextSnapshot sourceContext) {
    if (sourceContext == null) return null;
    return new QuoteReviewSourceContext(
        sourceContext.sourceType(),
        sourceContext.sourceChannel(),
        sourceContext.sourceReceivedAt(),
        sourceContext.createdByType(),
        sourceContext.conversionStatus());
  }

  private List<QuoteReviewCandidateLine> safeCandidateLines(QuoteSourceContextSnapshot sourceContext) {
    if (sourceContext == null) return List.of();
    return sourceContext.candidateLines().stream()
        .map(line -> new QuoteReviewCandidateLine(
            line.lineNumber(),
            line.rawSkuOrAlias(),
            line.description(),
            line.quantity(),
            line.uom(),
            line.requestedDate(),
            line.status()))
        .toList();
  }

  private void revalidateLine(UUID tenantId, DraftQuote quote, DraftQuoteLine line) {
    Product product = line.getProductId() == null ? null : productRepository.findByIdAndTenantIdAndDeletedAtIsNull(line.getProductId(), tenantId).orElse(null);
    CustomerAccount customer = quote.getCustomerAccountId() == null ? null : customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(quote.getCustomerAccountId(), tenantId).orElse(null);
    if (product == null || "REMOVED".equals(line.getStatus())) return;
    if (line.getQuantity() == null || line.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
      issueRepository.save(new QuoteValidationIssue(tenantId, quote.getId(), line.getId(), "INVALID_QUANTITY", "ERROR", true, "Quantity must be greater than zero", "{}", clock.instant()));
    }
    if ("UNKNOWN".equals(normalizeUom(line.getUom()))) {
      issueRepository.save(new QuoteValidationIssue(tenantId, quote.getId(), line.getId(), "UOM_UNRECOGNIZED", "WARNING", false, "UOM could not be normalized", "{}", clock.instant()));
    }
    UUID locationId = resolveLocation(tenantId, line.getRequestedLocation());
    PriceRule price = pricingService.selectPrice(tenantId, product, customer, locationId, line.getQuantity(), line.getUom()).orElse(null);
    if (price == null) {
      issueRepository.save(new QuoteValidationIssue(tenantId, quote.getId(), line.getId(), "PRICE_NOT_RESOLVED", "ERROR", true, "No active deterministic price rule matched corrected line", "{}", clock.instant()));
      return;
    }
    QuoteInventoryValidationService.InventoryValidation inventory = inventoryService.validate(tenantId, product.getId(), locationId, line.getQuantity());
    if (inventory.availableStock() != null && !inventory.sufficient()) {
      issueRepository.save(new QuoteValidationIssue(tenantId, quote.getId(), line.getId(), "INSUFFICIENT_STOCK", "ERROR", true, "Available stock is below corrected quantity", "{\"available\":\"" + inventory.availableStock() + "\"}", clock.instant()));
    }
    BigDecimal discount = line.getDiscountPercent() == null ? BigDecimal.ZERO : line.getDiscountPercent();
    QuoteMarginValidationService.MarginValidation margin = marginService.validate(tenantId, product, price.getUnitPrice(), discount);
    BigDecimal lineTotal = price.getUnitPrice().multiply(line.getQuantity()).multiply(BigDecimal.ONE.subtract(discount.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP))).setScale(2, RoundingMode.HALF_UP);
    line.applyTransactionPricing(discount, lineTotal, margin.marginPercent(), clock.instant());
    lineRepository.save(line);
    if (margin.violation()) {
      issueRepository.save(new QuoteValidationIssue(tenantId, quote.getId(), line.getId(), "MARGIN_BELOW_GUARDRAIL", "ERROR", true, "Corrected line still violates margin guardrail", "{\"marginPercent\":\"" + margin.marginPercent() + "\"}", clock.instant()));
    } else if (margin.approvalRequired()) {
      issueRepository.save(new QuoteValidationIssue(tenantId, quote.getId(), line.getId(), "MARGIN_APPROVAL_REQUIRED", "WARNING", true, "Corrected line requires margin approval", "{\"marginPercent\":\"" + margin.marginPercent() + "\"}", clock.instant()));
      approvalRepository.save(new QuoteApprovalRequest(tenantId, quote.getId(), line.getId(), "MARGIN_GUARDRAIL", "WARNING", "MARGIN_APPROVAL_REQUIRED", "Corrected line requires margin approval", clock.instant()));
    }
  }

  private QuoteReviewCommandResult result(UUID tenantId, String previousStatus, DraftQuote quote, String action) {
    List<QuoteValidationIssue> currentIssues = issues(tenantId, quote.getId());
    List<QuoteApprovalRequest> approvals = approvalRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(tenantId, quote.getId());
    return new QuoteReviewCommandResult(quote.getId(), previousStatus, quote.getStatus(), action, currentIssues.stream().map(ValidationIssue::from).toList(), reviewReasons(currentIssues, approvals, lineRepository.findByTenantIdAndDraftQuoteId(tenantId, quote.getId())), approvals.stream().anyMatch(approval -> "OPEN".equals(approval.getStatus())), validationSummary(currentIssues, approvals));
  }

  private List<SubstituteCandidate> substituteCandidates(UUID tenantId, DraftQuote quote, List<DraftQuoteLine> lines) {
    List<SubstituteCandidate> candidates = new ArrayList<>();
    for (DraftQuoteLine line : lines) {
      if (line.getProductId() == null || "REMOVED".equals(line.getStatus())) continue;
      candidates.addAll(substitutionService.suggest(tenantId, line.getProductId(), line.getRawSku(), line.getRawText(), quote.getCustomerAccountId(), line.getQuantity()).stream()
          .map(candidate -> new SubstituteCandidate(line.getId(), candidate.productId(), candidate.sku(), candidate.productName(), candidate.riskLevel().name(), candidate.reasonCode(), candidate.availableStock(), candidate.stockStatus().name(), candidate.requiresApproval(), candidate.blocked(), candidate.customerAccepted(), candidate.explanation()))
          .toList());
    }
    return candidates;
  }

  private ProductSubstitutionService.SubstituteCandidate substituteCandidate(UUID tenantId, DraftQuote quote, DraftQuoteLine line, UUID substituteProductId) {
    return substitutionService.suggest(tenantId, line.getProductId(), line.getRawSku(), line.getRawText(), quote.getCustomerAccountId(), line.getQuantity()).stream()
        .filter(candidate -> candidate.productId().equals(substituteProductId))
        .findFirst()
        .orElseThrow(() -> new QuoteLifecycleViolation("Substitute candidate is not compatible with this tenant-scoped quote line"));
  }

  private void resolveOpenIssues(UUID tenantId, UUID quoteId, UUID lineId, Set<String> issueCodes) {
    issueRepository.findByTenantIdAndDraftQuoteIdAndStatusOrderByCreatedAtAsc(tenantId, quoteId, "OPEN").stream()
        .filter(issue -> lineId == null || lineId.equals(issue.getDraftQuoteLineId()))
        .filter(issue -> issueCodes.isEmpty() || issueCodes.contains(issue.getIssueCode()))
        .forEach(issue -> issue.resolve(clock.instant()));
  }

  private DraftQuote quote(UUID tenantId, UUID quoteId) {
    return quoteRepository.findByIdAndTenantId(quoteId, tenantId).orElseThrow(() -> new NotFoundException("Draft quote not found: " + quoteId));
  }

  private DraftQuoteLine line(UUID tenantId, UUID quoteId, UUID lineId) {
    return lineRepository.findByIdAndTenantId(lineId, tenantId).filter(line -> quoteId.equals(line.getDraftQuoteId())).orElseThrow(() -> new NotFoundException("Draft quote line not found: " + lineId));
  }

  private QuoteValidationIssue issue(UUID tenantId, UUID quoteId, UUID issueId) {
    QuoteValidationIssue issue = issueRepository.findByIdAndTenantId(issueId, tenantId).orElseThrow(() -> new NotFoundException("Quote validation issue not found: " + issueId));
    if (!quoteId.equals(issue.getDraftQuoteId())) throw new NotFoundException("Quote validation issue not found: " + issueId);
    return issue;
  }

  private List<QuoteValidationIssue> issues(UUID tenantId, UUID quoteId) {
    return issueRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(tenantId, quoteId);
  }

  private void requireCorrectableLifecycle(DraftQuote quote) {
    if (TERMINAL_STATUSES.contains(quote.getStatus())) {
      throw new QuoteLifecycleViolation("Quote status " + quote.getStatus() + " cannot be corrected in review");
    }
  }

  private CommandContext context(UUID commandTenantId, UUID actorId) {
    try {
      UUID contextTenantId = TenantContext.requireTenantId();
      if (commandTenantId != null && !commandTenantId.equals(contextTenantId)) throw new IllegalArgumentException("Command tenantId does not match tenant context");
      return new CommandContext(contextTenantId, actorId);
    } catch (TenantContextMissingException ex) {
      throw ex;
    }
  }

  private void revalidated(DraftQuote quote, UUID actorId) {
    auditEventService.record("QUOTE_REVALIDATED", "DRAFT_QUOTE", quote.getId().toString(), actorId, "{\"tenantId\":\"" + quote.getTenantId() + "\",\"quoteId\":\"" + quote.getId() + "\",\"status\":\"" + quote.getStatus() + "\",\"validationSummary\":\"" + escape(validationSummary(issues(quote.getTenantId(), quote.getId()), approvalRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(quote.getTenantId(), quote.getId()))) + "\"}");
  }

  private void audit(String action, DraftQuote quote, UUID actorId, UUID lineId, UUID issueId, String previousValue, String newValue, String reasonCode, String note) {
    auditEventService.record(action, "DRAFT_QUOTE", quote.getId().toString(), actorId, "{\"tenantId\":\"" + quote.getTenantId() + "\",\"quoteId\":\"" + quote.getId() + "\",\"lineId\":\"" + (lineId == null ? "" : lineId) + "\",\"issueId\":\"" + (issueId == null ? "" : issueId) + "\",\"previousValue\":\"" + escape(previousValue) + "\",\"newValue\":\"" + escape(newValue) + "\",\"actorType\":\"USER\",\"reasonCode\":\"" + escape(reasonCode) + "\",\"note\":\"" + escape(note) + "\",\"validationSummary\":\"" + escape(validationSummary(issues(quote.getTenantId(), quote.getId()), approvalRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(quote.getTenantId(), quote.getId()))) + "\",\"externalExecution\":\"DISABLED\"}");
  }

  private PricingRiskSummary pricingSummary(DraftQuote quote, List<QuoteValidationIssue> issues, List<QuoteApprovalRequest> approvals) {
    return new PricingRiskSummary(quote.getSubtotalAmount(), quote.getDiscountAmount(), quote.getTotalAmount(), quote.getMarginPercent(), quoteHasIssue(issues, "MARGIN"), quoteHasIssue(issues, "DISCOUNT"), approvals.stream().anyMatch(approval -> "OPEN".equals(approval.getStatus())));
  }

  private List<String> reviewReasons(List<QuoteValidationIssue> issues, List<QuoteApprovalRequest> approvals, List<DraftQuoteLine> lines) {
    List<String> reasons = new ArrayList<>(issues.stream().filter(issue -> "OPEN".equals(issue.getStatus())).map(QuoteValidationIssue::getIssueCode).distinct().toList());
    reasons.addAll(approvals.stream().filter(approval -> "OPEN".equals(approval.getStatus())).map(QuoteApprovalRequest::getReasonCode).distinct().toList());
    reasons.addAll(lines.stream().filter(line -> Set.of("SUBSTITUTE_SUGGESTED", "SUBSTITUTE_APPROVAL_REQUIRED", "SUBSTITUTE_REJECTED", "SUBSTITUTE_BLOCKED", "NO_SAFE_SUBSTITUTE_FOUND").contains(line.getSubstituteDecisionStatus())).map(DraftQuoteLine::getSubstituteDecisionStatus).distinct().toList());
    return reasons.stream().distinct().toList();
  }

  private String validationSummary(List<QuoteValidationIssue> issues, List<QuoteApprovalRequest> approvals) {
    long openIssues = issues.stream().filter(issue -> "OPEN".equals(issue.getStatus())).count();
    long blockers = issues.stream().filter(issue -> "OPEN".equals(issue.getStatus()) && issue.isBlocking()).count();
    long openApprovals = approvals.stream().filter(approval -> "OPEN".equals(approval.getStatus())).count();
    return "openIssues=" + openIssues + ",blockingIssues=" + blockers + ",openApprovals=" + openApprovals;
  }

  private String nextAction(DraftQuote quote, List<QuoteValidationIssue> issues) {
    if (TERMINAL_STATUSES.contains(quote.getStatus())) return "NO_REVIEW_ACTION";
    if (issues.stream().anyMatch(issue -> "OPEN".equals(issue.getStatus()) && issue.isBlocking())) return "RESOLVE_BLOCKING_ISSUES";
    if ("PENDING_APPROVAL".equals(quote.getStatus())) return "APPROVAL_DECISION_REQUIRED";
    if (quote.isRequiresHumanReview()) return "OPERATOR_REVIEW_REQUIRED";
    return "READY_FOR_INTERNAL_LIFECYCLE";
  }

  private String highestSeverity(List<QuoteValidationIssue> issues) {
    return issues.stream().filter(issue -> "OPEN".equals(issue.getStatus())).map(QuoteValidationIssue::getSeverity).min(Comparator.comparingInt(this::severityRank)).orElse("NONE");
  }

  private int severityRank(String severity) {
    if ("ERROR".equals(severity)) return 0;
    if ("WARNING".equals(severity)) return 1;
    return 2;
  }

  private boolean quoteHasIssue(UUID tenantId, UUID quoteId, String issueType, String severity) {
    return issues(tenantId, quoteId).stream().anyMatch(issue -> (issueType == null || issueType.equals(issue.getIssueCode())) && (severity == null || severity.equals(issue.getSeverity())));
  }

  private boolean quoteHasIssue(List<QuoteValidationIssue> issues, String prefix) {
    return issues.stream().anyMatch(issue -> issue.getIssueCode().startsWith(prefix));
  }

  private UUID resolveLocation(UUID tenantId, String requestedLocation) {
    if (requestedLocation == null || requestedLocation.isBlank()) return null;
    return locationRepository.findByTenantIdAndCode(tenantId, requestedLocation.trim()).map(location -> location.getId()).orElse(null);
  }

  private static String normalizeUom(String uom) {
    if (uom == null || uom.isBlank()) return "UNKNOWN";
    String normalized = uom.trim().toLowerCase();
    if (Set.of("ea", "pcs", "pc", "unit", "units").contains(normalized)) return "EA";
    return "UNKNOWN";
  }

  private static boolean contains(String value, String needle) {
    return value != null && needle != null && value.toLowerCase().contains(needle.toLowerCase());
  }

  private static String firstNonBlank(String... values) {
    if (values == null) return null;
    for (String value : values) if (value != null && !value.isBlank()) return value;
    return null;
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private record CommandContext(UUID tenantId, UUID actorId) {}
}
