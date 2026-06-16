package com.orderpilot.domain.aiwork;

/**
 * OP-CAP-07A source object an AI work suggestion is attached to. These reuse existing OrderPilot
 * concepts (channel intake, operator review, quote work) so suggestions stay anchored to real,
 * tenant-owned context rather than free-floating AI output.
 */
public enum AiWorkSourceType {
  CHANNEL_MESSAGE,
  INBOUND_CHANNEL_EVENT,
  RFQ_HANDOFF,
  OPERATOR_REVIEW,
  QUOTE,
  QUOTE_TRANSACTION,
  SOURCE_CONTEXT
}
