package com.orderpilot.application.services.channel;

import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.api.dto.Stage11ADtos.CreateDraftQuoteFromRfqRequest;
import com.orderpilot.api.dto.Stage11ADtos.DraftQuoteResponse;
import com.orderpilot.application.services.workspace.RfqToDraftQuoteService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelRfqHandoff;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import com.orderpilot.domain.channel.ChannelRfqHandoffStatus;
import com.orderpilot.security.policy.ActorRole;
import java.util.List;
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

  private final ChannelRfqHandoffRepository handoffRepository;
  private final ChannelRfqHandoffService handoffService;
  private final RfqToDraftQuoteService draftQuoteService;

  public RfqHandoffDraftQuoteService(
      ChannelRfqHandoffRepository handoffRepository,
      ChannelRfqHandoffService handoffService,
      RfqToDraftQuoteService draftQuoteService) {
    this.handoffRepository = handoffRepository;
    this.handoffService = handoffService;
    this.draftQuoteService = draftQuoteService;
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
                List.of()),
            idempotencyKey);
    ChannelRfqHandoffResponse updatedHandoff =
        handoffService.markConverted(
            handoffId,
            "Draft quote " + draftQuote.quoteNumber() + " created from reviewed RFQ handoff.",
            actorId);
    return new RfqHandoffDraftQuoteResult(updatedHandoff, draftQuote);
  }

  public record RfqHandoffDraftQuoteResult(
      ChannelRfqHandoffResponse handoff, DraftQuoteResponse draftQuote) {}
}
