package com.orderpilot.domain.usage;

/**
 * OP-CAP-16B Usage Metering Foundation — the internal subsystem that originated a usage event. Used
 * for attribution and metrics only; it is a stable token and never carries identifying payload.
 */
public enum UsageSource {
  AI_ROUTER,
  EXTRACTION_PIPELINE,
  CHANNEL_INTAKE,
  WORKSPACE,
  RECONCILIATION,
  INTEGRATION,
  SYSTEM
}
