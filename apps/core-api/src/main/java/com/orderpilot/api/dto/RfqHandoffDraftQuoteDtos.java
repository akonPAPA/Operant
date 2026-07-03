package com.orderpilot.api.dto;

import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.api.dto.Stage11ADtos.DraftQuoteResponse;
import com.orderpilot.application.services.channel.RfqHandoffDraftQuoteService.RfqHandoffDraftQuoteResult;

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
}
