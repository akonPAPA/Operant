package com.orderpilot.application.services.modelruntime;

/** Output/action categories a model may suggest or may never own. */
public enum AiModelAdvisoryAction {
  FINDINGS,
  RISK_CLASSIFICATION,
  SUGGESTED_TESTS,
  CONFIDENCE,
  CAVEATS,
  NEXT_REVIEW_RECOMMENDATION,
  APPROVE_CHANGE_REQUEST,
  EXECUTE_CONNECTOR,
  MUTATE_BUSINESS_STATE,
  OVERRIDE_TENANT_ACTOR_STATUS_APPROVAL_EXECUTION,
  BYPASS_VALIDATION
}
