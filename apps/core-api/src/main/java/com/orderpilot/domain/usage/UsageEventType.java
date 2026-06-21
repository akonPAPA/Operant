package com.orderpilot.domain.usage;

/**
 * OP-CAP-16B Usage Metering Foundation — the kind of metered activity an append-only {@code
 * UsageEvent} records. This is a stable taxonomy token only; it never carries raw customer,
 * document, prompt, or AI-output text.
 */
public enum UsageEventType {
  /** A deterministic AI routing decision was produced (see Stage 16A {@code AiRoutingDecision}). */
  AI_ROUTING_DECISION,
  /** A runtime-control admission decision was recorded as safe evidence. */
  RUNTIME_CONTROL_DECISION,
  /** AI/heavy-worker input units were consumed for a workload. */
  AI_INPUT_CONSUMED,
  /** An inbound document was accepted for processing. */
  DOCUMENT_UPLOADED,
  /** An inbound channel message was received. */
  CHANNEL_MESSAGE_RECEIVED,
  /** A draft quote was created. */
  DRAFT_QUOTE_CREATED,
  /** A draft order was created. */
  DRAFT_ORDER_CREATED,
  /** A reconciliation run was executed. */
  RECONCILIATION_RUN,
  /** An integration/connector sync was executed. */
  INTEGRATION_SYNC,
  /** Generic metered activity not covered by a more specific type. */
  GENERIC_METERED
}
