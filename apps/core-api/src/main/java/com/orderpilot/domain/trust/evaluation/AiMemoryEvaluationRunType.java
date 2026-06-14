package com.orderpilot.domain.trust.evaluation;

/**
 * OP-CAP-19 Layer C — Projected Memory Evaluation Harness.
 *
 * The kind of governance evaluation a run performs over advisory retrieval / projected memory. Evaluation
 * is deterministic, local, and read-only with respect to memory — it never calls an external model and
 * never mutates memory or business state.
 */
public enum AiMemoryEvaluationRunType {
  ADVISORY_RETRIEVAL_REGRESSION,
  TENANT_ISOLATION,
  UNSAFE_MEMORY_EXCLUSION,
  RANKING_STABILITY,
  OPERATOR_CORRECTION_PROJECTION
}
