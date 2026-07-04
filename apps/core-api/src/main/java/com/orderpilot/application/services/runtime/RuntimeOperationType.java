package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — the kind of runtime operation a guard protects.
 *
 * <p>Used to look up an endpoint cost weight and a rate-limit rule, and to derive the default usage
 * metric a quota policy is evaluated against. It is a stable token only; it never carries payload.
 */
public enum RuntimeOperationType {
  AI_ROUTING_DECISION,
  AI_DOCUMENT_EXTRACTION,
  DOCUMENT_UPLOAD,
  CHANNEL_MESSAGE_RECEIVED,
  BULK_IMPORT,
  RECONCILIATION_RUN,
  SEARCH_QUERY,
  REPORT_GENERATED,
  // OP-CAP-16G: gated at the advisory AI explanation/summary generation boundary
  // (AiWorkService.createSuggestion, immediately before the provider call).
  AI_VALIDATION_EXPLANATION,
  // OP-CAP-27B: RFQ/AI/demo path runtime-control checkpoints. Each is a distinct, deterministic,
  // operator-initiated boundary on the visible demo flow (deterministic demo RFQ handoff creation ->
  // review-required draft quote creation -> safe terminal demo decision). They are rate/backpressure
  // gated only (no quota metric, no entitlement/plan coupling) so the demo path is denial-safe without
  // introducing billing semantics. The AI advisory suggestion boundary on this same path is already
  // gated by AI_VALIDATION_EXPLANATION (the shared advisory-generation guard) and is not duplicated.
  DEMO_RFQ_HANDOFF_CREATE,
  RFQ_HANDOFF_DRAFT_QUOTE_CREATE,
  RFQ_HANDOFF_DEMO_DECISION
}
