package com.orderpilot.domain.usage;

/**
 * OP-CAP-16B Usage Metering Foundation — how a quota policy would be applied. In Stage 16B nothing
 * is enforced in live request paths: {@code checkQuota} only returns an advisory decision regardless
 * of mode. Live enforcement is deferred to Stage 16C.
 */
public enum QuotaEnforcementMode {
  /** Observe only — never block, even when over limit. */
  MONITOR,
  /** Intended to block when over limit (not wired into live paths until 16C). */
  ENFORCE
}
