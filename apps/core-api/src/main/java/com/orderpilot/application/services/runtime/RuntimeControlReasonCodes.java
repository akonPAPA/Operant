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
}
