package com.orderpilot.domain.trust.evaluation;

/**
 * OP-CAP-19 Layer C — Projected Memory Evaluation Harness.
 *
 * Lifecycle status shared by evaluation runs, cases, and results.
 */
public enum AiMemoryEvaluationStatus {
  PENDING,
  RUNNING,
  PASSED,
  FAILED,
  SKIPPED
}
