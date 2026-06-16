package com.orderpilot.domain.trust.events;

/**
 * OP-CAP-18 Trust/AI Event Projector Runtime.
 *
 * The kind of approved, sanitized, internal trust/AI event a projector can consume. These describe
 * already-decided deterministic workflow outcomes — never raw documents/prompts/messages.
 */
public enum TrustAiEventType {
  DOCUMENT_TRUST_COMPLETED,
  TRUST_RISK_DECIDED,
  TRUST_RISK_OVERRIDDEN,
  PAYMENT_OBLIGATION_UPDATED,
  PAYMENT_ALLOCATION_RECORDED,
  COUNTERPARTY_TRUST_UPDATED,
  OPERATOR_CORRECTION_RECORDED,
  AI_MEMORY_INVALIDATED,
  AI_RUNTIME_TRACE_RECORDED
}
