package com.orderpilot.application.services.runtime;

/**
 * Product-level workload taxonomy for runtime-control admission decisions. These tokens are stable and
 * safe for audit/metrics; they carry no payload, provider name, source id, or tenant internals.
 */
public enum RuntimeWorkloadType {
  AI_EXTRACTION,
  AI_VALIDATION_ASSIST,
  DOCUMENT_PROCESSING,
  CHANNEL_MESSAGE_PROCESSING,
  BOT_RUNTIME,
  RECONCILIATION,
  CONNECTOR_SYNC,
  OTHER;

  public static RuntimeWorkloadType from(RuntimeOperationType operationType, AiWorkloadType aiType) {
    if (operationType == RuntimeOperationType.AI_DOCUMENT_EXTRACTION) {
      return AI_EXTRACTION;
    }
    if (operationType == RuntimeOperationType.AI_VALIDATION_EXPLANATION) {
      return AI_VALIDATION_ASSIST;
    }
    if (operationType == RuntimeOperationType.DOCUMENT_UPLOAD) {
      return DOCUMENT_PROCESSING;
    }
    if (operationType == RuntimeOperationType.CHANNEL_MESSAGE_RECEIVED) {
      return CHANNEL_MESSAGE_PROCESSING;
    }
    if (operationType == RuntimeOperationType.RECONCILIATION_RUN) {
      return RECONCILIATION;
    }
    if (aiType == AiWorkloadType.DOCUMENT_EXTRACTION) {
      return AI_EXTRACTION;
    }
    if (aiType == AiWorkloadType.VALIDATION_EXPLANATION) {
      return AI_VALIDATION_ASSIST;
    }
    return OTHER;
  }

  public boolean aiBacked() {
    return this == AI_EXTRACTION || this == AI_VALIDATION_ASSIST;
  }
}
