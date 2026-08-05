package com.orderpilot.aibot.domain.botruntime;

import com.orderpilot.aibot.domain.botdefinition.BotDefinitionVersion;
import com.orderpilot.aibot.domain.botdefinition.BotIntentDefinition;
import com.orderpilot.aibot.domain.capability.BotCapabilityKey;
import com.orderpilot.aibot.domain.capability.CapabilityRegistry;
import java.math.BigDecimal;
import java.util.Objects;

/** Preview/runtime policy: AI proposes intent; backend selects allowlisted action. */
public final class BotRuntimePolicy {

  public static final BigDecimal DEFAULT_MIN_CONFIDENCE = new BigDecimal("0.70");

  private BotRuntimePolicy() {}

  public static void assertPreviewable(BotDefinitionVersion version) {
    Objects.requireNonNull(version, "version");
    if (!version.state().isPreviewable()) {
      throw new IllegalStateException("bot_not_previewable");
    }
  }

  public static Decision decide(
      BotDefinitionVersion version,
      String proposedIntentKey,
      BigDecimal confidence,
      boolean safetyRisk,
      boolean explicitHumanRequest) {
    assertPreviewable(version);
    BigDecimal conf = confidence == null ? BigDecimal.ZERO : confidence;

    if (safetyRisk && version.configuration().handoffPolicy().onSafetyRisk()) {
      return Decision.handoff(BotCapabilityKey.BOT_HUMAN_HANDOFF, "SAFETY_RISK");
    }
    if (explicitHumanRequest && version.configuration().handoffPolicy().onExplicitHumanRequest()) {
      return Decision.handoff(BotCapabilityKey.BOT_HUMAN_HANDOFF, "EXPLICIT_HUMAN_REQUEST");
    }

    var intentOpt = version.findIntent(proposedIntentKey);
    if (intentOpt.isEmpty()) {
      if (version.configuration().handoffPolicy().onUnknownIntent()) {
        return Decision.handoff(BotCapabilityKey.BOT_HUMAN_HANDOFF, "UNKNOWN_INTENT");
      }
      return Decision.action(BotCapabilityKey.BOT_UNSUPPORTED_RESPONSE, "UNSUPPORTED");
    }

    BotIntentDefinition intent = intentOpt.get();
    BigDecimal threshold =
        intent.minimumConfidence() == null ? DEFAULT_MIN_CONFIDENCE : intent.minimumConfidence();
    if (conf.compareTo(threshold) < 0 && intent.handoffOnLowConfidence()) {
      return Decision.handoff(BotCapabilityKey.BOT_HUMAN_HANDOFF, "LOW_CONFIDENCE");
    }

    BotCapabilityKey action =
        CapabilityRegistry.resolve(intent.actionKey())
            .orElseThrow(() -> new IllegalStateException("capability_denied"));
    return Decision.action(action, "MAPPED");
  }

  public record Decision(BotCapabilityKey capabilityKey, String reason, boolean handoffRequired) {
    public static Decision action(BotCapabilityKey key, String reason) {
      return new Decision(key, reason, key == BotCapabilityKey.BOT_HUMAN_HANDOFF);
    }

    public static Decision handoff(BotCapabilityKey key, String reason) {
      return new Decision(key, reason, true);
    }
  }
}
