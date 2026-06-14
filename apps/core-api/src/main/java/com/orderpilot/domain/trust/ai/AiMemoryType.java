package com.orderpilot.domain.trust.ai;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * The kind of bounded, sanitized knowledge a memory record holds. This describes the SHAPE of the fact,
 * not raw content — no raw documents/prompts/messages are ever represented here.
 */
public enum AiMemoryType {
  FACT,
  HINT,
  PATTERN,
  TEMPLATE,
  CORRECTION,
  SUMMARY,
  EXPLANATION
}
