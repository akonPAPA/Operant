package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-27 Runtime Control Mainline — stable reason tokens that are specific to the runtime-control
 * orchestration step and not already provided by {@link AiWorkloadReasonCodes} (classification) or
 * {@link RuntimeGuardReasonCodes} (entitlement/quota/rate). Safe for metrics/audit; never raw input.
 */
public final class RuntimeControlReasonCodes {
  private RuntimeControlReasonCodes() {}

  /** A duplicate idempotent request was detected before classification or guard evaluation. */
  public static final String DEDUP_IDEMPOTENT_HIT = "DEDUP_IDEMPOTENT_HIT";

  public static final String RUNTIME_CONTROL_DISABLED = "RUNTIME_CONTROL_DISABLED";
  public static final String AI_WORKLOAD_DISABLED = "AI_WORKLOAD_DISABLED";
  public static final String REQUEST_COST_LIMIT_EXCEEDED = "REQUEST_COST_LIMIT_EXCEEDED";
  public static final String BACKPRESSURE_QUEUE_DEPTH_EXCEEDED = "BACKPRESSURE_QUEUE_DEPTH_EXCEEDED";
  public static final String SYNC_COST_PROMOTED_TO_ASYNC = "SYNC_COST_PROMOTED_TO_ASYNC";
}
