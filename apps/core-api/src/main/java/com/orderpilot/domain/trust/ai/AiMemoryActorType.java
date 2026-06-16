package com.orderpilot.domain.trust.ai;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * Who/what triggered a memory governance action (create/supersede/invalidate/expire). AI is never an
 * authoritative writer of business data; this only attributes governance actions on advisory memory.
 */
public enum AiMemoryActorType {
  OPERATOR,
  SYSTEM,
  PROJECTOR
}
