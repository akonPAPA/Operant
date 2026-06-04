package com.orderpilot.application.services.bot;

import com.orderpilot.domain.bot.BotFlow;
import com.orderpilot.domain.bot.BotFlowMode;
import com.orderpilot.domain.bot.BotFlowPolicyReason;
import java.util.UUID;

/**
 * OP-CAP-06B deterministic decision describing whether and how a controlled bot flow may run for a
 * given connection configuration. It only ever constrains the runtime; it never grants autonomy,
 * outbound sends, or business-record approval.
 */
public record BotFlowPolicyDecision(
    BotFlow flow,
    boolean allowed,
    BotFlowMode mode,
    BotFlowPolicyReason reasonCode,
    boolean requiresHandoff,
    boolean mayExposePrice,
    boolean mayExposeAvailability,
    boolean mayCreateDraft,
    String warningMessage,
    UUID configId) {

  public static BotFlowPolicyDecision blocked(BotFlow flow, BotFlowPolicyReason reason, String warning, UUID configId) {
    return new BotFlowPolicyDecision(flow, false, BotFlowMode.DISABLED, reason, true, false, false, false, warning, configId);
  }
}
