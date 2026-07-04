package com.orderpilot.domain.aiwork;

/**
 * Stable public AI Work schema identifiers.
 *
 * <p>The schema is derived from the backend-owned work type. Provider payloads cannot select a
 * different public schema.
 */
public enum AiWorkSchemaVersion {
  AI_WORK_SCHEMA_V1_REQUEST_SUMMARY,
  AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION,
  AI_WORK_SCHEMA_V1_CUSTOMER_REPLY_DRAFT,
  AI_WORK_SCHEMA_V1_VALIDATION_EXPLANATION;

  public static AiWorkSchemaVersion forWorkType(String workType) {
    if (workType == null || workType.isBlank()) {
      throw new IllegalArgumentException("AI work type is required");
    }
    return forWorkType(AiWorkType.valueOf(workType));
  }

  public static AiWorkSchemaVersion forWorkType(AiWorkType workType) {
    return switch (workType) {
      case REQUEST_SUMMARY, SOURCE_CONTEXT_DIGEST -> AI_WORK_SCHEMA_V1_REQUEST_SUMMARY;
      case NEXT_ACTION_SUGGESTION -> AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION;
      case CUSTOMER_REPLY_DRAFT -> AI_WORK_SCHEMA_V1_CUSTOMER_REPLY_DRAFT;
      case VALIDATION_EXPLANATION -> AI_WORK_SCHEMA_V1_VALIDATION_EXPLANATION;
    };
  }
}
