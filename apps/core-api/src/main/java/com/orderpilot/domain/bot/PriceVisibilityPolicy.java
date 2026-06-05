package com.orderpilot.domain.bot;

/** OP-CAP-06B policy controlling when (if ever) the bot may expose price information. */
public enum PriceVisibilityPolicy {
  /** Price is never shown by the bot. Price intents route to operator review. */
  NEVER,
  /** Price may be considered only when the customer identity is resolved. */
  IDENTIFIED_CUSTOMER_ONLY,
  /** Price may be considered only for an explicitly authorized customer context. */
  AUTHORIZED_CUSTOMER_ONLY
}
