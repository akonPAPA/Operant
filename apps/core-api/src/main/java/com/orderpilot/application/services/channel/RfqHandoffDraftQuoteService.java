package com.orderpilot.application.services.channel;

import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.api.dto.Stage11ADtos.CreateDraftQuoteFromRfqRequest;
import com.orderpilot.api.dto.Stage11ADtos.DraftQuoteResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.workspace.RfqToDraftQuoteService;
import com.orderpilot.application.services.workspace.RfqTextLineExtractor;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelRfqHandoff;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import com.orderpilot.domain.channel.ChannelRfqHandoffStatus;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.security.policy.ActorRole;
import com.orderpilot.security.policy.TenantPolicyException;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trusted bridge from an operator-reviewed RFQ handoff to an internal draft quote.
 *
 * <p>The route supplies only the handoff handle. Tenant, actor, role, source text, workflow status,
 * and idempotency are resolved by the backend. The resulting quote remains review-required and no
 * connector, ChangeRequest, outbox, or external execution is invoked.
 */
@Service
public class RfqHandoffDraftQuoteService {
  private static final String SOURCE_TYPE = "RFQ_HANDOFF";
  private static final String IDEMPOTENCY_PREFIX = "rfq-handoff-draft-quote:";
  private static final String TERMINAL_STATE = "SAFE_DEMO_TERMINAL";
  private static final String EXTERNAL_EXECUTION = "DISABLED";
  private static final String CONNECTOR_ACTION = "NOT_INVOKED";
  private static final String OUTBOX_STATUS = "NOT_REQUESTED";
  private static final Set<String> DECIDABLE_QUOTE_STATES =
      Set.of("NEEDS_REVIEW", "SUBSTITUTION_REVIEW", "READY_FOR_APPROVAL");

  private final ChannelRfqHandoffRepository handoffRepository;
  private final ChannelRfqHandoffService handoffService;
  private final RfqToDraftQuoteService draftQuoteService;
  private final DraftQuoteRepository draftQuoteRepository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public RfqHandoffDraftQuoteService(
      ChannelRfqHandoffRepository handoffRepository,
      ChannelRfqHandoffService handoffService,
      RfqToDraftQuoteService draftQuoteService,
      DraftQuoteRepository draftQuoteRepository,
      AuditEventService auditEventService,
      Clock clock) {
    this.handoffRepository = handoffRepository;
    this.handoffService = handoffService;
    this.draftQuoteService = draftQuoteService;
    this.draftQuoteRepository = draftQuoteRepository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public RfqHandoffDraftQuoteResult createDraftQuote(
      UUID handoffId, UUID actorId, ActorRole actorRole) {
    if (handoffId == null) {
      throw new IllegalArgumentException("RFQ handoff id is required");
    }
    if (actorId == null || actorRole == null) {
      throw new IllegalArgumentException("Verified operator authority is required");
    }

    UUID tenantId = TenantContext.requireTenantId();
    ChannelRfqHandoff handoff =
        handoffRepository
            .findWithLockByIdAndTenantId(handoffId, tenantId)
            .orElseThrow(() -> new NotFoundException("RFQ handoff not found"));
    String idempotencyKey = IDEMPOTENCY_PREFIX + handoffId;

    var existing = draftQuoteService.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      return new RfqHandoffDraftQuoteResult(handoffService.get(handoffId), existing.get());
    }
    if (handoff.getStatus() != ChannelRfqHandoffStatus.IN_REVIEW) {
      throw new IllegalArgumentException(
          "RFQ handoff must be in review before a draft quote can be created");
    }

    DraftQuoteResponse draftQuote =
        draftQuoteService.createFromRfq(
            new CreateDraftQuoteFromRfqRequest(
                actorId,
                actorRole.name(),
                SOURCE_TYPE,
                null,
                null,
                null,
                handoff.getRequestText(),
                RfqTextLineExtractor.extractSingleLine(handoff.getRequestText())),
            idempotencyKey);
    ChannelRfqHandoffResponse updatedHandoff =
        handoffService.markConverted(
            handoffId,
            "Draft quote " + draftQuote.quoteNumber() + " created from reviewed RFQ handoff.",
            actorId);
    return new RfqHandoffDraftQuoteResult(updatedHandoff, draftQuote);
  }

  /**
   * Records the operator's terminal demo decision without approving or executing the quote.
   *
   * <p>The quote is located from the backend-owned handoff idempotency relationship. Client-supplied
   * tenant, actor, source, status, approval, or execution authority is never accepted.
   */
  @Transactional
  public RfqHandoffDecisionResult decide(
      UUID handoffId,
      UUID actorId,
      ActorRole actorRole,
      String requestedDecision,
      String requestedNote) {
    if (handoffId == null) {
      throw new IllegalArgumentException("RFQ handoff id is required");
    }
    if (actorId == null || actorRole == null) {
      throw new TenantPolicyException("Verified operator authority is required");
    }
    if (actorRole != ActorRole.OPERATOR && actorRole != ActorRole.SALES_QUOTE_MANAGER) {
      throw new TenantPolicyException("Quote operator role is required");
    }

    DemoDecision decision = DemoDecision.parse(requestedDecision);
    String note = requireNote(requestedNote);
    UUID tenantId = TenantContext.requireTenantId();
    ChannelRfqHandoff handoff =
        handoffRepository
            .findWithLockByIdAndTenantId(handoffId, tenantId)
            .orElseThrow(() -> new NotFoundException("RFQ handoff not found"));
    if (handoff.getStatus() != ChannelRfqHandoffStatus.CONVERTED) {
      throw new IllegalArgumentException(
          "RFQ handoff must have a draft quote before an operator decision");
    }

    DraftQuote quote =
        draftQuoteRepository
            .findWithLockByTenantIdAndIdempotencyKey(
                tenantId, IDEMPOTENCY_PREFIX + handoffId)
            .orElseThrow(() -> new NotFoundException("RFQ handoff draft quote not found"));
    if (!SOURCE_TYPE.equals(quote.getSourceType())) {
      throw new IllegalArgumentException("Draft quote is not owned by the RFQ handoff flow");
    }
    if (!DECIDABLE_QUOTE_STATES.contains(quote.getStatus())) {
      throw new IllegalArgumentException(
          "Draft quote is not in a valid state for an operator demo decision");
    }

    String previousState = quote.getStatus();
    quote.transition(
        decision.quoteState(),
        quote.getValidationStatus(),
        quote.isRequiresHumanReview(),
        actorId,
        clock.instant());
    quote = draftQuoteRepository.save(quote);
    auditEventService.record(
        "RFQ_HANDOFF_DEMO_DECISION_RECORDED",
        "DRAFT_QUOTE",
        quote.getId().toString(),
        actorId,
        "{\"decision\":\""
            + decision.name()
            + "\",\"previousState\":\""
            + escape(previousState)
            + "\",\"newState\":\""
            + decision.quoteState()
            + "\",\"note\":\""
            + escape(note)
            + "\",\"externalExecution\":\"DISABLED\",\"connectorAction\":\"NOT_INVOKED\","
            + "\"outboxStatus\":\"NOT_REQUESTED\"}");

    return new RfqHandoffDecisionResult(
        handoffId,
        quote.getId(),
        quote.getQuoteNumber(),
        decision.name(),
        quote.getStatus(),
        TERMINAL_STATE,
        EXTERNAL_EXECUTION,
        CONNECTOR_ACTION,
        OUTBOX_STATUS);
  }

  private static String requireNote(String requestedNote) {
    if (requestedNote == null || requestedNote.isBlank()) {
      throw new IllegalArgumentException("Operator decision note is required");
    }
    String note = requestedNote.trim();
    if (note.length() > 500) {
      throw new IllegalArgumentException("Operator decision note must not exceed 500 characters");
    }
    return note;
  }

  private static String escape(String value) {
    return value == null
        ? ""
        : value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
  }

  private enum DemoDecision {
    COMPLETE_DEMO("DEMO_COMPLETED"),
    DECLINE_DEMO("DEMO_DECLINED");

    private final String quoteState;

    DemoDecision(String quoteState) {
      this.quoteState = quoteState;
    }

    private String quoteState() {
      return quoteState;
    }

    private static DemoDecision parse(String value) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("Operator decision is required");
      }
      try {
        return DemoDecision.valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        throw new IllegalArgumentException(
            "Operator decision must be COMPLETE_DEMO or DECLINE_DEMO");
      }
    }
  }

  public record RfqHandoffDraftQuoteResult(
      ChannelRfqHandoffResponse handoff, DraftQuoteResponse draftQuote) {}

  public record RfqHandoffDecisionResult(
      UUID handoffId,
      UUID draftQuoteId,
      String quoteNumber,
      String decision,
      String quoteState,
      String terminalState,
      String externalExecution,
      String connectorAction,
      String outboxStatus) {}
}
