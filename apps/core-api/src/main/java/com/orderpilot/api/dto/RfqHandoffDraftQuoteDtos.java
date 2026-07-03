package com.orderpilot.api.dto;

import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.api.dto.Stage11ADtos.DraftQuoteResponse;
import com.orderpilot.application.services.channel.RfqHandoffDraftQuoteService.RfqHandoffDecisionResult;
import com.orderpilot.application.services.channel.RfqHandoffDraftQuoteService.RfqHandoffDraftQuoteResult;
import java.util.UUID;

/** Public response for the reviewed RFQ handoff to draft quote bridge. */
public final class RfqHandoffDraftQuoteDtos {
  private RfqHandoffDraftQuoteDtos() {}

  public record RfqHandoffDraftQuoteResponse(
      ChannelRfqHandoffResponse handoff,
      DraftQuoteResponse draftQuote,
      String auditStatus,
      String outboxStatus,
      String externalWriteSafety) {

    public static RfqHandoffDraftQuoteResponse from(RfqHandoffDraftQuoteResult result) {
      return new RfqHandoffDraftQuoteResponse(
          result.handoff(), result.draftQuote(), "RECORDED", "NOT_REQUESTED", "NO_EXTERNAL_WRITE");
    }
  }

  /** Business intent only. Tenant, actor, status, approval, and execution remain backend-owned. */
  public record RfqHandoffDecisionRequest(String decision, String note) {}

  /** Safe terminal demo projection. No internal audit, idempotency, source, or connector data. */
  public record RfqHandoffDecisionResponse(
      UUID handoffId,
      UUID draftQuoteId,
      String quoteNumber,
      String decision,
      String quoteState,
      String terminalState,
      String auditStatus,
      String safetySummary,
      String externalExecution,
      String connectorAction,
      String outboxStatus) {

    public static RfqHandoffDecisionResponse from(RfqHandoffDecisionResult result) {
      return new RfqHandoffDecisionResponse(
          result.handoffId(),
          result.draftQuoteId(),
          result.quoteNumber(),
          result.decision(),
          result.quoteState(),
          result.terminalState(),
          "RECORDED",
          "Operator decision recorded without approval or external execution.",
          result.externalExecution(),
          result.connectorAction(),
          result.outboxStatus());
    }
  }
}
