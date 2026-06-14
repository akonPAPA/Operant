package com.orderpilot.domain.trust.ai;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * How much an AI memory record may influence advisory ranking. {@code HUMAN_APPROVED} and
 * {@code SYSTEM_DERIVED} can rank higher and bypass the low-confidence floor, but NO authority level
 * makes memory authoritative — deterministic backend services always remain the source of truth.
 */
public enum AiMemoryAuthorityLevel {
  LOW,
  MEDIUM,
  HIGH,
  HUMAN_APPROVED,
  SYSTEM_DERIVED
}
