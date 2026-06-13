package com.orderpilot.application.services.runtime;

/**
 * Advisory model tier a workload would be routed to. {@code RULES_ONLY} means no AI model is needed;
 * {@code HUMAN_REVIEW} means the workload should be gated to an operator rather than auto-processed.
 *
 * <p>Stage 16A records the decision only; it does not bind any concrete provider.
 */
public enum ModelTier {
  NONE,
  RULES_ONLY,
  SMALL_LOCAL,
  MEDIUM,
  LARGE,
  HUMAN_REVIEW
}
