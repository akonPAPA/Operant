package com.orderpilot.domain.trust.evaluation;

/**
 * OP-CAP-19 Layer C — Projected Memory Evaluation Harness.
 *
 * What a single evaluation case asserts about an advisory retrieval outcome.
 */
public enum AiMemoryEvaluationCaseType {
  EXPECT_TOP_MATCH,
  EXPECT_EXCLUDED_INVALIDATED,
  EXPECT_EXCLUDED_SUPERSEDED,
  EXPECT_TENANT_ISOLATED,
  EXPECT_SCORE_ABOVE_THRESHOLD
}
