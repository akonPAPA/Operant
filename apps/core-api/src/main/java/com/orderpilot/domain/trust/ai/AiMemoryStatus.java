package com.orderpilot.domain.trust.ai;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * Lifecycle status of an AI memory record. Only {@code ACTIVE} (and not past its TTL) records are
 * returned as usable memory. {@code EXPIRED}/{@code INVALIDATED}/{@code SUPERSEDED} are terminal — the
 * record is preserved for audit but never served as active memory.
 */
public enum AiMemoryStatus {
  ACTIVE,
  EXPIRED,
  INVALIDATED,
  SUPERSEDED
}
