package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage12ADtos.*;
import com.orderpilot.api.dto.Stage12ADtos;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.common.tenant.TenantContextMissingException;
import com.orderpilot.domain.workspace.*;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteApprovalStateMachineService {
  private static final Set<String> TERMINAL_STATUSES = Set.of("REJECTED", "CHANGES_REQUESTED", "EXPIRED", "CONVERTED_TO_INTERNAL_ORDER");
  private static final Set<String> APPROVAL_RESOLVABLE_ISSUES = Set.of("MARGIN_APPROVAL_REQUIRED", "DISCOUNT_APPROVAL_REQUIRED");

  private final DraftQuoteRepository quoteRepository;
  private final QuoteValidationIssueRepository issueRepository;
  private final QuoteApprovalRequestRepository approvalRequestRepository;
  private final QuoteApprovalDecisionRepository decisionRepository;
  private final QuoteInternalOrderBoundaryRepository boundaryRepository;
  private final AuditEventService auditEventService;
  private final JsonSupport jsonSupport;
  private final Clock clock;

  public QuoteApprovalStateMachineService(DraftQuoteRepository quoteRepository, QuoteValidationIssueRepository issueRepository, QuoteApprovalRequestRepository approvalRequestRepository, QuoteApprovalDecisionRepository decisionRepository, QuoteInternalOrderBoundaryRepository boundaryRepository, AuditEventService auditEventService, JsonSupport jsonSupport, Clock clock) {
    this.quoteRepository = quoteRepository;
    this.issueRepository = issueRepository;
    this.approvalRequestRepository = approvalRequestRepository;
    this.decisionRepository = decisionRepository;
    this.boundaryRepository = boundaryRepository;
    this.auditEventService = auditEventService;
    this.jsonSupport = jsonSupport;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public QuoteApprovalStateResponse getQuoteApprovalState(UUID quoteId) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftQuote quote = quote(tenantId, quoteId);
    return state(tenantId, quote);
  }

  @Transactional
  public QuoteApprovalCommandResponse approveQuote(UUID quoteId, QuoteApprovalDecisionCommand command) {
    CommandContext ctx = context(command);
    DraftQuote quote = quoteForTransition(ctx.tenantId(), quoteId);
    if ("APPROVED".equals(quote.getStatus())) {
      return commandResponse(ctx.tenantId(), quote, "APPROVED", "APPROVE");
    }
    requireNotTerminalForDecision(quote);
    List<QuoteValidationIssue> hardBlockers = hardBlockers(ctx.tenantId(), quoteId);
    if (!hardBlockers.isEmpty()) {
      auditBlocked(ctx, quote, "APPROVE", hardBlockers);
      throw new QuoteLifecycleViolation("Quote has unresolved blocking validation issues");
    }
    String previous = quote.getStatus();
    List<QuoteApprovalRequest> openApprovals = openApprovals(ctx.tenantId(), quoteId);
    if (openApprovals.isEmpty() && quote.isRequiresHumanReview()) {
      throw new QuoteLifecycleViolation("Quote requires approval but has no open approval request to decide");
    }
    for (QuoteApprovalRequest request : openApprovals) {
      request.decide("APPROVED", reason(command), ctx.actorId(), clock.instant());
    }
    approvalRequestRepository.saveAll(openApprovals);
    resolveApprovalIssues(ctx.tenantId(), quoteId);
    quote.transition("APPROVED", "VALIDATED", false, ctx.actorId(), clock.instant());
    quote = quoteRepository.save(quote);
    UUID auditCorrelationId = UUID.randomUUID();
    QuoteApprovalDecision decision = recordDecision(ctx, quote, command == null ? null : command.approvalRequestId(), "APPROVE", previous, "APPROVED", reason(command), reasons(openApprovals), List.of(), auditCorrelationId);
    auditEventService.record("quote.approved", "DRAFT_QUOTE", quote.getId().toString(), ctx.actorId(), metadata(ctx.tenantId(), quote.getId(), previous, "APPROVED", "APPROVE", reason(command), reasons(openApprovals), List.of(), auditCorrelationId, null));
    auditEventService.record("approval.decision.recorded", "QUOTE_APPROVAL_DECISION", decision.getId().toString(), ctx.actorId(), metadata(ctx.tenantId(), quote.getId(), previous, "APPROVED", "APPROVE", reason(command), reasons(openApprovals), List.of(), auditCorrelationId, null));
    return commandResponse(ctx.tenantId(), quote, previous, "APPROVE");
  }

  @Transactional
  public QuoteApprovalCommandResponse rejectQuote(UUID quoteId, QuoteApprovalDecisionCommand command) {
    return terminalDecision(quoteId, command, "REJECT", "REJECTED", "quote.rejected");
  }

  @Transactional
  public QuoteApprovalCommandResponse requestQuoteChanges(UUID quoteId, QuoteApprovalDecisionCommand command) {
    if (isBlank(reason(command))) {
      throw new QuoteLifecycleViolation("Reason/comment is required when requesting quote changes");
    }
    return terminalDecision(quoteId, command, "REQUEST_CHANGES", "CHANGES_REQUESTED", "quote.changes_requested");
  }

  @Transactional
  public QuoteApprovalCommandResponse convertApprovedQuoteToInternalDraftOrder(UUID quoteId, QuoteApprovalDecisionCommand command) {
    CommandContext ctx = context(command);
    DraftQuote quote = quoteForTransition(ctx.tenantId(), quoteId);
    if ("CONVERTED_TO_INTERNAL_ORDER".equals(quote.getStatus())) {
      QuoteInternalOrderBoundary existing = boundaryRepository.findByTenantIdAndDraftQuoteId(ctx.tenantId(), quoteId).orElseThrow(() -> new QuoteLifecycleViolation("Converted quote is missing its internal boundary record"));
      return commandResponse(ctx.tenantId(), quote, "CONVERTED_TO_INTERNAL_ORDER", "CONVERT", existing.getId(), existing.getChangeRequestId(), externalExecutionEnabled(existing.getExternalExecutionStatus()));
    }
    if (!"APPROVED".equals(quote.getStatus())) {
      auditBlocked(ctx, quote, "CONVERT", List.of());
      throw new QuoteLifecycleViolation("Only APPROVED quotes can be converted to an internal draft order boundary");
    }
    if (!hardBlockers(ctx.tenantId(), quoteId).isEmpty()) {
      throw new QuoteLifecycleViolation("Quote has unresolved blocking validation issues");
    }
    QuoteInternalOrderBoundary boundary = boundaryRepository.findByTenantIdAndDraftQuoteId(ctx.tenantId(), quoteId)
        .orElseGet(() -> boundaryRepository.save(new QuoteInternalOrderBoundary(ctx.tenantId(), quoteId, "INTERNAL_DRAFT_ORDER_CANDIDATE", "EXTERNAL_EXECUTION_DISABLED", null, idempotency(command, ctx.tenantId(), quoteId), ctx.actorId(), clock.instant())));
    String previous = quote.getStatus();
    quote.transition("CONVERTED_TO_INTERNAL_ORDER", "VALIDATED", false, ctx.actorId(), clock.instant());
    quote = quoteRepository.save(quote);
    UUID auditCorrelationId = UUID.randomUUID();
    auditEventService.record("quote.converted_to_internal_order", "DRAFT_QUOTE", quote.getId().toString(), ctx.actorId(), metadata(ctx.tenantId(), quoteId, previous, quote.getStatus(), "CONVERT", reason(command), List.of(), List.of(), auditCorrelationId, boundary.getId()));
    return commandResponse(ctx.tenantId(), quote, previous, "CONVERT", boundary.getId(), boundary.getChangeRequestId(), externalExecutionEnabled(boundary.getExternalExecutionStatus()));
  }

  private QuoteApprovalCommandResponse terminalDecision(UUID quoteId, QuoteApprovalDecisionCommand command, String decisionType, String newStatus, String auditAction) {
    CommandContext ctx = context(command);
    DraftQuote quote = quoteForTransition(ctx.tenantId(), quoteId);
    requireNotTerminalForDecision(quote);
    if ("APPROVED".equals(quote.getStatus())) {
      throw new QuoteLifecycleViolation("Approved quote cannot be " + newStatus.toLowerCase());
    }
    String previous = quote.getStatus();
    List<QuoteApprovalRequest> openApprovals = openApprovals(ctx.tenantId(), quoteId);
    for (QuoteApprovalRequest request : openApprovals) {
      request.decide("REJECTED".equals(newStatus) ? "REJECTED" : "CHANGES_REQUESTED", reason(command), ctx.actorId(), clock.instant());
    }
    approvalRequestRepository.saveAll(openApprovals);
    quote.transition(newStatus, "NEEDS_REVIEW", false, ctx.actorId(), clock.instant());
    quote.appendNote(reason(command), clock.instant());
    quote = quoteRepository.save(quote);
    UUID auditCorrelationId = UUID.randomUUID();
    QuoteApprovalDecision decision = recordDecision(ctx, quote, command == null ? null : command.approvalRequestId(), decisionType, previous, newStatus, reason(command), reasons(openApprovals), hardBlockers(ctx.tenantId(), quoteId).stream().map(QuoteValidationIssue::getIssueCode).toList(), auditCorrelationId);
    auditEventService.record(auditAction, "DRAFT_QUOTE", quote.getId().toString(), ctx.actorId(), metadata(ctx.tenantId(), quoteId, previous, newStatus, decisionType, reason(command), reasons(openApprovals), List.of(), auditCorrelationId, null));
    auditEventService.record("approval.decision.recorded", "QUOTE_APPROVAL_DECISION", decision.getId().toString(), ctx.actorId(), metadata(ctx.tenantId(), quoteId, previous, newStatus, decisionType, reason(command), reasons(openApprovals), List.of(), auditCorrelationId, null));
    return commandResponse(ctx.tenantId(), quote, previous, decisionType);
  }

  private QuoteApprovalStateResponse state(UUID tenantId, DraftQuote quote) {
    List<QuoteValidationIssue> issues = issueRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(tenantId, quote.getId());
    List<QuoteApprovalRequest> approvals = approvalRequestRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(tenantId, quote.getId());
    QuoteApprovalDecision latest = decisionRepository.findByTenantIdAndDraftQuoteIdOrderByDecidedAtDesc(tenantId, quote.getId()).stream().findFirst().orElse(null);
    QuoteInternalOrderBoundary boundary = boundaryRepository.findByTenantIdAndDraftQuoteId(tenantId, quote.getId()).orElse(null);
    return new QuoteApprovalStateResponse(
        quote.getId(),
        quote.getStatus(),
        approvals.stream().anyMatch(request -> "OPEN".equals(request.getStatus())) || quote.isRequiresHumanReview(),
        issues.stream().filter(issue -> issue.isBlocking() && "OPEN".equals(issue.getStatus())).map(ValidationIssue::from).toList(),
        approvals.stream().filter(request -> "OPEN".equals(request.getStatus())).map(QuoteApprovalRequest::getReasonCode).distinct().toList(),
        approvals.stream().map(ApprovalRequest::from).toList(),
        Stage12ADtos.ApprovalDecision.from(latest),
        boundary == null ? null : boundary.getId(),
        boundary == null ? null : boundary.getChangeRequestId(),
        boundary != null && externalExecutionEnabled(boundary.getExternalExecutionStatus()));
  }

  /**
   * Maps the lower-layer external execution status to a safe business boolean. Operant keeps external
   * (ERP/connector) writes disabled, so this is false unless the boundary is explicitly enabled — the
   * raw internal status string is never exposed on the response.
   */
  private static boolean externalExecutionEnabled(String externalExecutionStatus) {
    return "EXTERNAL_EXECUTION_ENABLED".equals(externalExecutionStatus);
  }

  private QuoteApprovalCommandResponse commandResponse(UUID tenantId, DraftQuote quote, String previous, String decision) {
    return commandResponse(tenantId, quote, previous, decision, null, null, false);
  }

  private QuoteApprovalCommandResponse commandResponse(UUID tenantId, DraftQuote quote, String previous, String decision, UUID internalDraftOrderId, UUID changeRequestId, boolean externalExecutionEnabled) {
    List<QuoteValidationIssue> blocking = issueRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(tenantId, quote.getId()).stream()
        .filter(issue -> issue.isBlocking() && "OPEN".equals(issue.getStatus()))
        .toList();
    List<QuoteApprovalRequest> open = openApprovals(tenantId, quote.getId());
    return new QuoteApprovalCommandResponse(quote.getId(), previous, quote.getStatus(), !open.isEmpty() || quote.isRequiresHumanReview(), decision, blocking.stream().map(ValidationIssue::from).toList(), reasons(open), internalDraftOrderId, changeRequestId, externalExecutionEnabled);
  }

  private void requireNotTerminalForDecision(DraftQuote quote) {
    if (TERMINAL_STATUSES.contains(quote.getStatus())) {
      throw new QuoteLifecycleViolation("Quote status " + quote.getStatus() + " does not allow another approval decision");
    }
  }

  private List<QuoteValidationIssue> hardBlockers(UUID tenantId, UUID quoteId) {
    return issueRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(tenantId, quoteId).stream()
        .filter(issue -> issue.isBlocking() && "OPEN".equals(issue.getStatus()))
        .filter(issue -> "ERROR".equals(issue.getSeverity()) || !APPROVAL_RESOLVABLE_ISSUES.contains(issue.getIssueCode()))
        .toList();
  }

  private void resolveApprovalIssues(UUID tenantId, UUID quoteId) {
    issueRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(tenantId, quoteId).stream()
        .filter(issue -> issue.isBlocking() && "OPEN".equals(issue.getStatus()))
        .filter(issue -> APPROVAL_RESOLVABLE_ISSUES.contains(issue.getIssueCode()))
        .forEach(issue -> issue.resolve(clock.instant()));
  }

  private List<QuoteApprovalRequest> openApprovals(UUID tenantId, UUID quoteId) {
    return approvalRequestRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(tenantId, quoteId).stream()
        .filter(request -> "OPEN".equals(request.getStatus()))
        .toList();
  }

  private QuoteApprovalDecision recordDecision(CommandContext ctx, DraftQuote quote, UUID approvalRequestId, String decision, String previous, String next, String comment, List<String> resolvedReasons, List<String> blockingReasons, UUID auditCorrelationId) {
    return decisionRepository.save(new QuoteApprovalDecision(ctx.tenantId(), quote.getId(), approvalRequestId, decision, comment, ctx.actorId(), clock.instant(), previous, next, jsonSupport.writeObject(resolvedReasons), jsonSupport.writeObject(blockingReasons), auditCorrelationId));
  }

  private void auditBlocked(CommandContext ctx, DraftQuote quote, String decision, List<QuoteValidationIssue> blockers) {
    UUID auditCorrelationId = UUID.randomUUID();
    auditEventService.record("quote.conversion_blocked", "DRAFT_QUOTE", quote.getId().toString(), ctx.actorId(), metadata(ctx.tenantId(), quote.getId(), quote.getStatus(), quote.getStatus(), decision, "Blocked transition", List.of(), blockers.stream().map(QuoteValidationIssue::getIssueCode).toList(), auditCorrelationId, null));
  }

  private DraftQuote quote(UUID tenantId, UUID quoteId) {
    if (quoteId == null) {
      throw new IllegalArgumentException("quoteId is required");
    }
    return quoteRepository.findByIdAndTenantId(quoteId, tenantId).orElseThrow(() -> new NotFoundException("Draft quote not found: " + quoteId));
  }

  private DraftQuote quoteForTransition(UUID tenantId, UUID quoteId) {
    if (quoteId == null) {
      throw new IllegalArgumentException("quoteId is required");
    }
    return quoteRepository.findWithLockByIdAndTenantId(quoteId, tenantId).orElseThrow(() -> new NotFoundException("Draft quote not found: " + quoteId));
  }

  private CommandContext context(QuoteApprovalDecisionCommand command) {
    UUID commandTenantId = command == null ? null : command.tenantId();
    try {
      UUID contextTenantId = TenantContext.requireTenantId();
      if (commandTenantId != null && !commandTenantId.equals(contextTenantId)) {
        throw new IllegalArgumentException("Command tenantId does not match tenant context");
      }
      return new CommandContext(contextTenantId, command == null ? null : command.actorId());
    } catch (TenantContextMissingException ex) {
      throw ex;
    }
  }

  private static String reason(QuoteApprovalDecisionCommand command) {
    if (command == null) {
      return null;
    }
    return !isBlank(command.reason()) ? command.reason() : command.comment();
  }

  private static String idempotency(QuoteApprovalDecisionCommand command, UUID tenantId, UUID quoteId) {
    if (command != null && !isBlank(command.idempotencyKey())) {
      return command.idempotencyKey().trim();
    }
    return "stage12b:internal-order:" + tenantId + ":" + quoteId;
  }

  private static List<String> reasons(List<QuoteApprovalRequest> requests) {
    return requests.stream().map(QuoteApprovalRequest::getReasonCode).distinct().toList();
  }

  private String metadata(UUID tenantId, UUID quoteId, String previous, String next, String decision, String reason, List<String> resolvedReasons, List<String> blockingReasons, UUID auditCorrelationId, UUID internalOrderId) {
    java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
    metadata.put("tenantId", tenantId.toString());
    metadata.put("quoteId", quoteId.toString());
    metadata.put("previousStatus", text(previous));
    metadata.put("newStatus", text(next));
    metadata.put("decision", text(decision));
    metadata.put("reason", text(reason));
    metadata.put("resolvedReasons", resolvedReasons == null ? List.of() : resolvedReasons);
    metadata.put("blockingReasons", blockingReasons == null ? List.of() : blockingReasons);
    metadata.put("internalDraftOrderId", internalOrderId == null ? "" : internalOrderId.toString());
    metadata.put("auditCorrelationId", auditCorrelationId.toString());
    metadata.put("externalExecution", "DISABLED");
    return jsonSupport.writeObject(metadata);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private record CommandContext(UUID tenantId, UUID actorId) {}
}
