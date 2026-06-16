package com.orderpilot.domain.trust;

/**
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * Immutable outcome of {@code DocumentTrustDecisionPolicy}: the computed risk level, a numeric
 * {@code riskScore} clamped to 0..100, and the deterministic routing flags. This is an operator-review
 * routing decision, not a fraud verdict.
 *
 * <ul>
 *   <li>LOW / MEDIUM -&gt; {@code CONTINUE_WITH_WARNING} (continue, surface a warning)</li>
 *   <li>HIGH -&gt; {@code REQUIRES_REVIEW} (human review required before irreversible action)</li>
 *   <li>CRITICAL -&gt; {@code BLOCK_AUTOMATION} (automation blocked; human review/approval required)</li>
 * </ul>
 */
public record DocumentTrustDecision(
    TrustRiskLevel riskLevel,
    int riskScore,
    boolean requiresHumanReview,
    boolean blocksAutomation,
    String state) {

  public static final int MIN_RISK_SCORE = 0;
  public static final int MAX_RISK_SCORE = 100;

  public static final String STATE_CONTINUE_WITH_WARNING = "CONTINUE_WITH_WARNING";
  public static final String STATE_REQUIRES_REVIEW = "REQUIRES_REVIEW";
  public static final String STATE_BLOCK_AUTOMATION = "BLOCK_AUTOMATION";

  public DocumentTrustDecision {
    riskScore = clampScore(riskScore);
  }

  public static int clampScore(int rawScore) {
    return Math.max(MIN_RISK_SCORE, Math.min(MAX_RISK_SCORE, rawScore));
  }

  public static DocumentTrustDecision of(TrustRiskLevel riskLevel, int riskScore) {
    boolean requiresHumanReview = riskLevel.atLeast(TrustRiskLevel.HIGH);
    boolean blocksAutomation = riskLevel == TrustRiskLevel.CRITICAL;
    String state;
    if (riskLevel == TrustRiskLevel.CRITICAL) {
      state = STATE_BLOCK_AUTOMATION;
    } else if (riskLevel == TrustRiskLevel.HIGH) {
      state = STATE_REQUIRES_REVIEW;
    } else {
      state = STATE_CONTINUE_WITH_WARNING;
    }
    return new DocumentTrustDecision(riskLevel, riskScore, requiresHumanReview, blocksAutomation, state);
  }
}
