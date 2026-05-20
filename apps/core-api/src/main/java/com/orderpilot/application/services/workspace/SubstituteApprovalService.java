package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage11ADtos.*;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.ProductSubstitutionService;
import com.orderpilot.application.services.ProductSubstitutionService.SubstituteCandidate;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.CompensationPlanRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.integration.ConnectorSandboxExecutionRepository;
import com.orderpilot.domain.workspace.*;
import com.orderpilot.security.policy.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubstituteApprovalService {
  private static final Set<String> SUBSTITUTE_ISSUES = Set.of(
      "INSUFFICIENT_STOCK",
      "PRODUCT_OUT_OF_STOCK_SUBSTITUTE_AVAILABLE",
      "SUBSTITUTE_REQUIRES_APPROVAL",
      "SUBSTITUTE_BLOCKED_FOR_CUSTOMER",
      "COMPATIBILITY_UNVERIFIED",
      "NO_SAFE_SUBSTITUTE_FOUND");

  private final DraftQuoteRepository quoteRepository;
  private final DraftQuoteLineRepository lineRepository;
  private final QuoteValidationIssueRepository issueRepository;
  private final ProductSubstitutionService productSubstitutionService;
  private final QuoteLifecycleService lifecycleService;
  private final TenantPolicyService tenantPolicyService;
  private final AuditEventService auditEventService;
  private final ConnectorCommandRepository connectorCommandRepository;
  private final ConnectorSandboxExecutionRepository sandboxExecutionRepository;
  private final CompensationPlanRepository compensationPlanRepository;
  private final RfqToDraftQuoteService responseService;
  private final Clock clock;

  public SubstituteApprovalService(DraftQuoteRepository quoteRepository, DraftQuoteLineRepository lineRepository, QuoteValidationIssueRepository issueRepository, ProductSubstitutionService productSubstitutionService, QuoteLifecycleService lifecycleService, TenantPolicyService tenantPolicyService, AuditEventService auditEventService, ConnectorCommandRepository connectorCommandRepository, ConnectorSandboxExecutionRepository sandboxExecutionRepository, CompensationPlanRepository compensationPlanRepository, RfqToDraftQuoteService responseService, Clock clock) {
    this.quoteRepository = quoteRepository;
    this.lineRepository = lineRepository;
    this.issueRepository = issueRepository;
    this.productSubstitutionService = productSubstitutionService;
    this.lifecycleService = lifecycleService;
    this.tenantPolicyService = tenantPolicyService;
    this.auditEventService = auditEventService;
    this.connectorCommandRepository = connectorCommandRepository;
    this.sandboxExecutionRepository = sandboxExecutionRepository;
    this.compensationPlanRepository = compensationPlanRepository;
    this.responseService = responseService;
    this.clock = clock;
  }

  @Transactional
  public DraftQuoteResponse approveSubstitute(UUID quoteId, UUID lineId, SubstituteDecisionCommand command) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftQuote quote = quote(tenantId, quoteId);
    DraftQuoteLine line = line(tenantId, quoteId, lineId);
    UUID candidateProductId = requireCandidateProduct(command);
    SubstituteCandidate candidate = findCandidate(tenantId, quote, line, candidateProductId);
    if (candidate.blocked()) {
      line.setSubstituteDecision("SUBSTITUTE_BLOCKED", candidate.productId(), "CUSTOMER_BLOCKED_RULE", command.actorId(), command.note(), clock.instant());
      lineRepository.save(line);
      lifecycleService.recalculate(quote);
      audit("SUBSTITUTE_CANDIDATE_REJECTED", quote, line, command.actorId(), candidate, "CUSTOMER_BLOCKED_RULE", command.note(), "SUBSTITUTE_APPROVAL_REQUIRED", "SUBSTITUTE_BLOCKED");
      throw new QuoteLifecycleViolation("Blocked customer substitute cannot be approved");
    }
    if (candidate.requiresApproval()) {
      tenantPolicyService.requireAllowed(policyContext(tenantId, quote.getId(), command, TenantPolicyAction.APPROVE_RISKY_SUBSTITUTE, candidate.riskLevel().name()));
    }
    if ("COMPATIBILITY_UNVERIFIED".equals(candidate.reasonCode())) {
      throw new QuoteLifecycleViolation("Cannot approve substitute without safe compatibility explanation");
    }
    String previous = line.getSubstituteDecisionStatus();
    line.setSubstituteDecision("SUBSTITUTE_APPROVED", candidate.productId(), candidate.reasonCode(), command.actorId(), command.note(), clock.instant());
    lineRepository.save(line);
    resolveSubstituteIssues(tenantId, quoteId, lineId);
    DraftQuote next = lifecycleService.recalculate(quote);
    audit("SUBSTITUTE_CANDIDATE_APPROVED", next, line, command.actorId(), candidate, candidate.reasonCode(), command.note(), previous, "SUBSTITUTE_APPROVED");
    return responseService.get(quoteId);
  }

  @Transactional
  public DraftQuoteResponse rejectSubstitute(UUID quoteId, UUID lineId, SubstituteDecisionCommand command) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftQuote quote = quote(tenantId, quoteId);
    DraftQuoteLine line = line(tenantId, quoteId, lineId);
    UUID candidateProductId = requireCandidateProduct(command);
    SubstituteCandidate candidate = findCandidate(tenantId, quote, line, candidateProductId);
    String previous = line.getSubstituteDecisionStatus();
    boolean hasOtherSafe = candidates(tenantId, quote, line).stream().anyMatch(c -> !c.productId().equals(candidateProductId) && !c.blocked() && !c.requiresApproval() && !"COMPATIBILITY_UNVERIFIED".equals(c.reasonCode()));
    line.setSubstituteDecision(hasOtherSafe ? "SUBSTITUTE_REJECTED" : "NO_SAFE_SUBSTITUTE_FOUND", null, hasOtherSafe ? "OPERATOR_REJECTED_CANDIDATE" : "NO_SAFE_SUBSTITUTE_FOUND", command.actorId(), command.note(), clock.instant());
    lineRepository.save(line);
    if (!hasOtherSafe) {
      issueRepository.save(new QuoteValidationIssue(tenantId, quoteId, lineId, "NO_SAFE_SUBSTITUTE_FOUND", "ERROR", true, "Operator rejected the available substitute and no safe candidate remains", "{\"rejectedSubstituteProductId\":\"" + candidateProductId + "\"}", clock.instant()));
    }
    DraftQuote next = lifecycleService.recalculate(quote);
    audit("SUBSTITUTE_CANDIDATE_REJECTED", next, line, command.actorId(), candidate, line.getSubstituteDecisionReasonCode(), command.note(), previous, line.getSubstituteDecisionStatus());
    return responseService.get(quoteId);
  }

  @Transactional
  public DraftQuoteResponse resetSubstituteDecision(UUID quoteId, UUID lineId, SubstituteDecisionCommand command) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftQuote quote = quote(tenantId, quoteId);
    DraftQuoteLine line = line(tenantId, quoteId, lineId);
    String previous = line.getSubstituteDecisionStatus();
    line.resetSubstituteDecision(clock.instant());
    lineRepository.save(line);
    DraftQuote next = lifecycleService.recalculate(quote);
    audit("SUBSTITUTE_DECISION_RESET", next, line, command == null ? null : command.actorId(), null, "RESET", command == null ? null : command.note(), previous, "SUBSTITUTE_SUGGESTED");
    return responseService.get(quoteId);
  }

  @Transactional
  public DraftQuoteResponse markReady(UUID quoteId, QuoteLifecycleCommand command) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftQuote quote = quote(tenantId, quoteId);
    lifecycleService.requireReadyForApproval(tenantId, quote);
    String previous = quote.getStatus();
    quote.transition("READY_FOR_APPROVAL", "VALIDATED", true, command.actorId(), clock.instant());
    quote = quoteRepository.save(quote);
    auditQuote("QUOTE_MARKED_READY_FOR_APPROVAL", quote, command.actorId(), command.reason(), previous, "READY_FOR_APPROVAL");
    return responseService.get(quoteId);
  }

  @Transactional
  public DraftQuoteResponse approveQuote(UUID quoteId, QuoteLifecycleCommand command) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftQuote quote = quote(tenantId, quoteId);
    lifecycleService.requireReadyForApproval(tenantId, quote);
    tenantPolicyService.requireAllowed(policyContext(tenantId, quoteId, command, TenantPolicyAction.APPROVE_QUOTE, null));
    String previous = quote.getStatus();
    quote.transition("APPROVED", "VALIDATED", false, command.actorId(), clock.instant());
    quote = quoteRepository.save(quote);
    auditQuote("QUOTE_APPROVED_INTERNAL", quote, command.actorId(), command.reason(), previous, "APPROVED");
    assertNoExternalSideEffects();
    return responseService.get(quoteId);
  }

  @Transactional
  public DraftQuoteResponse rejectQuote(UUID quoteId, QuoteLifecycleCommand command) {
    return terminalQuote(quoteId, command, "REJECTED", "QUOTE_REJECTED");
  }

  @Transactional
  public DraftQuoteResponse cancelQuote(UUID quoteId, QuoteLifecycleCommand command) {
    return terminalQuote(quoteId, command, "CANCELLED", "QUOTE_CANCELLED");
  }

  private DraftQuoteResponse terminalQuote(UUID quoteId, QuoteLifecycleCommand command, String status, String action) {
    UUID tenantId = TenantContext.requireTenantId();
    DraftQuote quote = quote(tenantId, quoteId);
    String previous = quote.getStatus();
    quote.transition(status, "NEEDS_REVIEW", false, command.actorId(), clock.instant());
    quote.appendNote(command.reason(), clock.instant());
    quote = quoteRepository.save(quote);
    auditQuote(action, quote, command.actorId(), command.reason(), previous, status);
    return responseService.get(quoteId);
  }

  private DraftQuote quote(UUID tenantId, UUID quoteId) {
    return quoteRepository.findByIdAndTenantId(quoteId, tenantId).orElseThrow(() -> new NotFoundException("Draft quote not found: " + quoteId));
  }

  private DraftQuoteLine line(UUID tenantId, UUID quoteId, UUID lineId) {
    return lineRepository.findByTenantIdAndDraftQuoteId(tenantId, quoteId).stream()
        .filter(line -> line.getId().equals(lineId))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Draft quote line not found: " + lineId));
  }

  private UUID requireCandidateProduct(SubstituteDecisionCommand command) {
    if (command == null || command.substituteProductId() == null) {
      throw new QuoteLifecycleViolation("substituteProductId is required");
    }
    return command.substituteProductId();
  }

  private SubstituteCandidate findCandidate(UUID tenantId, DraftQuote quote, DraftQuoteLine line, UUID candidateProductId) {
    return candidates(tenantId, quote, line).stream()
        .filter(candidate -> candidate.productId().equals(candidateProductId))
        .findFirst()
        .orElseThrow(() -> new QuoteLifecycleViolation("Substitute candidate is not attached to this quote line"));
  }

  private List<SubstituteCandidate> candidates(UUID tenantId, DraftQuote quote, DraftQuoteLine line) {
    return productSubstitutionService.suggest(tenantId, line.getProductId(), line.getRawSku(), line.getRawText(), quote.getCustomerAccountId(), line.getQuantity());
  }

  private void resolveSubstituteIssues(UUID tenantId, UUID quoteId, UUID lineId) {
    issueRepository.findByTenantIdAndDraftQuoteIdAndDraftQuoteLineIdAndStatusOrderByCreatedAtAsc(tenantId, quoteId, lineId, "OPEN").stream()
        .filter(issue -> SUBSTITUTE_ISSUES.contains(issue.getIssueCode()))
        .forEach(issue -> issue.resolve(clock.instant()));
  }

  private TenantPolicyContext policyContext(UUID tenantId, UUID quoteId, SubstituteDecisionCommand command, TenantPolicyAction action, String riskLevel) {
    return TenantPolicyContext.builder()
        .tenantId(tenantId)
        .targetTenantId(tenantId)
        .actorId(command.actorId())
        .actorRoles(Set.of(parseRole(command.actorRole())))
        .action(action)
        .resourceType(ResourceType.QUOTE)
        .resourceId(quoteId)
        .riskLevel(riskLevel)
        .systemActor(false)
        .build();
  }

  private TenantPolicyContext policyContext(UUID tenantId, UUID quoteId, QuoteLifecycleCommand command, TenantPolicyAction action, String riskLevel) {
    return TenantPolicyContext.builder()
        .tenantId(tenantId)
        .targetTenantId(tenantId)
        .actorId(command.actorId())
        .actorRoles(Set.of(parseRole(command.actorRole())))
        .action(action)
        .resourceType(ResourceType.QUOTE)
        .resourceId(quoteId)
        .riskLevel(riskLevel)
        .systemActor(false)
        .build();
  }

  private ActorRole parseRole(String role) {
    if (role == null || role.isBlank()) {
      return ActorRole.OPERATOR;
    }
    return ActorRole.valueOf(role);
  }

  private void audit(String action, DraftQuote quote, DraftQuoteLine line, UUID actorId, SubstituteCandidate candidate, String reasonCode, String note, String previousState, String newState) {
    String metadata = "{\"quoteId\":\"" + quote.getId() + "\",\"quoteLineId\":\"" + line.getId() + "\",\"originalProductId\":\"" + line.getProductId() + "\",\"substituteProductId\":\"" + (candidate == null ? "" : candidate.productId()) + "\",\"reasonCode\":\"" + escape(reasonCode) + "\",\"note\":\"" + escape(note) + "\",\"previousState\":\"" + escape(previousState) + "\",\"newState\":\"" + escape(newState) + "\"}";
    auditEventService.record(action, "DRAFT_QUOTE", quote.getId().toString(), actorId, metadata);
  }

  private void auditQuote(String action, DraftQuote quote, UUID actorId, String reason, String previousState, String newState) {
    auditEventService.record(action, "DRAFT_QUOTE", quote.getId().toString(), actorId, "{\"quoteId\":\"" + quote.getId() + "\",\"reason\":\"" + escape(reason) + "\",\"previousState\":\"" + escape(previousState) + "\",\"newState\":\"" + escape(newState) + "\",\"externalExecution\":\"DISABLED\"}");
  }

  private void assertNoExternalSideEffects() {
    if (connectorCommandRepository.count() != 0 || sandboxExecutionRepository.count() != 0 || compensationPlanRepository.count() != 0) {
      throw new QuoteLifecycleViolation("Unexpected external side effect record detected");
    }
  }

  private String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
