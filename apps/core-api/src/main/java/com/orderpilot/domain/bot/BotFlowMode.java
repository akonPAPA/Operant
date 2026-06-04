package com.orderpilot.domain.bot;

/**
 * OP-CAP-06B controlled flow execution mode.
 *
 * <p>Deterministic policy values only. None of these enable autonomous external execution,
 * outbound sends, or business-record approval. The most permissive value still produces a
 * controlled draft or a safe, non-committal response routed through the existing runtime.
 */
public enum BotFlowMode {
  /** Flow is off. Inbound messages of this flow route to safe handoff/not-bridged. */
  DISABLED,
  /** Flow may run but its outcome is always routed to operator review (no direct disclosure). */
  OPERATOR_REVIEW_ONLY,
  /** Flow may create a reviewable internal draft (e.g. RFQ) through existing command services. */
  CONTROLLED_DRAFT,
  /** Flow may return a safe, bounded response (e.g. greeting, in-stock yes/no) with no promises. */
  CONTROLLED_RESPONSE
}
