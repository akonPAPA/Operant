package com.orderpilot.domain.trust.ai;

/**
 * OP-CAP-19 Layer B — Advisory AI Memory Retrieval.
 *
 * Explainability codes attached to a ranked advisory hint (or used to document why a candidate was
 * excluded). They make the deterministic ranking auditable and keep the advisory nature explicit. A hint
 * carrying any of these codes is still advisory and low-authority — never authoritative.
 */
public enum AiAdvisoryReasonCode {
  HUMAN_APPROVED,
  EXACT_KEY_MATCH,
  SAME_SOURCE_TYPE,
  TASK_NAMESPACE_MATCH,
  HIGH_CONFIDENCE,
  RECENT_MEMORY,
  SYSTEM_DERIVED,
  EXCLUDED_INVALIDATED,
  EXCLUDED_SUPERSEDED,
  LOW_CONFIDENCE
}
