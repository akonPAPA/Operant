package com.orderpilot.application.services.runtime;

/**
 * Stable reason-code tokens emitted by {@link AiWorkloadClassifier}. These are safe for metrics,
 * logs and audit — they describe the decision path, never the input content.
 */
public final class AiWorkloadReasonCodes {

  public static final String STRUCTURED_IDENTIFIER_RULES_PATH = "structured_identifier_rules_path";
  public static final String SHORT_CHAT_INTENT = "short_chat_intent";
  public static final String SMALL_WORKLOAD_LOCAL = "small_workload_local";
  public static final String MEDIUM_DOCUMENT_ASYNC = "medium_document_async";
  public static final String LARGE_DOCUMENT_ASYNC = "large_document_async";
  public static final String BULK_REQUIRES_REVIEW = "bulk_requires_review";
  public static final String SUSPICIOUS_PROMPT_INJECTION_REVIEW = "suspicious_prompt_injection_review";
  public static final String EMPTY_INPUT_RULES_ONLY = "empty_input_rules_only";
  public static final String UNKNOWN_WORKLOAD_REVIEW = "unknown_workload_review";

  private AiWorkloadReasonCodes() {}
}
