package com.orderpilot.domain.bot;

/**
 * OP-CAP-06B controlled bot flow identity.
 *
 * <p>A small, fixed set of business flows the controlled bot runtime supports. This is NOT a
 * generic flow-graph / no-code builder vocabulary; values map 1:1 to existing {@link BotIntent}
 * handling and may only be enabled, disabled, or routed to operator review.
 */
public enum BotFlow {
  GREETING,
  AVAILABILITY,
  PRICE,
  RFQ,
  SUBSTITUTE,
  ORDER_STATUS,
  HUMAN_HANDOFF,
  UNKNOWN;

  /** Map an intent classified by the runtime to its controlled flow. */
  public static BotFlow fromIntent(BotIntent intent) {
    if (intent == null) {
      return UNKNOWN;
    }
    return switch (intent) {
      case GREETING -> GREETING;
      case CHECK_AVAILABILITY, PRODUCT_AVAILABILITY_QUESTION -> AVAILABILITY;
      case CHECK_PRICE, PRICE_QUESTION -> PRICE;
      case REQUEST_QUOTE, RFQ_REQUEST -> RFQ;
      case SUGGEST_SUBSTITUTE, SUBSTITUTE_QUESTION -> SUBSTITUTE;
      case ORDER_OR_QUOTE_STATUS, ORDER_STATUS_QUESTION -> ORDER_STATUS;
      case HUMAN_HANDOFF, HUMAN_HELP_REQUEST -> HUMAN_HANDOFF;
      case UNSUPPORTED_REQUEST_SAFE_REPLY, UNKNOWN -> UNKNOWN;
    };
  }
}
