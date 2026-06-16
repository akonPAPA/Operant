package com.orderpilot.domain.trust.ai;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * Outcome of one AI/runtime workload, recorded as safe metadata only. {@code FALLBACK_USED} marks runs
 * where the deterministic fallback was taken because memory/AI was absent or rejected.
 */
public enum AiRuntimeStatus {
  SUCCEEDED,
  FAILED,
  REJECTED,
  SKIPPED,
  FALLBACK_USED
}
