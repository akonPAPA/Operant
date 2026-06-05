package com.orderpilot.domain.aiwork;

/**
 * OP-CAP-07A AI Agent Work Layer — the controlled set of advisory work types the AI Work Assistant
 * may produce for an operator. Every type is advisory only and never performs a business mutation.
 */
public enum AiWorkType {
  /** Summarize an inbound message/document/review context for an operator. */
  REQUEST_SUMMARY,
  /** Explain why validation issues exist, using existing validation/source-context data. */
  VALIDATION_EXPLANATION,
  /** Draft a safe customer-facing response. Draft only — never sent by this layer. */
  CUSTOMER_REPLY_DRAFT,
  /** Suggest possible internal next actions for the operator to choose from. */
  NEXT_ACTION_SUGGESTION,
  /** Produce a structured digest of source context and evidence for the operator. */
  SOURCE_CONTEXT_DIGEST
}
