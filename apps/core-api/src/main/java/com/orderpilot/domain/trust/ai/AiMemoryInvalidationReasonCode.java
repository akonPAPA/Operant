package com.orderpilot.domain.trust.ai;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * Deterministic reason an AI memory record changed away from {@code ACTIVE}. Recorded on the append-only
 * {@link AiMemoryInvalidationEvent} trace so every status change is audit-explainable.
 */
public enum AiMemoryInvalidationReasonCode {
  USER_INVALIDATED,
  SOURCE_UPDATED,
  CONFLICTING_EVIDENCE,
  EXPIRED,
  SUPERSEDED_BY_NEW_VERSION,
  LOW_CONFIDENCE,
  POLICY_CHANGE,
  TENANT_PURGE
}
