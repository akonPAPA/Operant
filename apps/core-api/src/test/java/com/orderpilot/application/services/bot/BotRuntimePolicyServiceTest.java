package com.orderpilot.application.services.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.domain.bot.BotFlow;
import com.orderpilot.domain.bot.BotFlowMode;
import com.orderpilot.domain.bot.BotFlowPolicyReason;
import com.orderpilot.domain.bot.InventoryFreshnessPolicy;
import com.orderpilot.domain.bot.PriceVisibilityPolicy;
import com.orderpilot.domain.bot.UnknownCustomerMode;
import com.orderpilot.domain.channel.ChannelBotRuntimeConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-06B pure policy decision tests. {@link BotRuntimePolicyService#decide} performs no I/O,
 * so the repository dependency is unused here.
 */
class BotRuntimePolicyServiceTest {
  private static final Instant NOW = Instant.parse("2026-06-04T00:00:00Z");
  private final BotRuntimePolicyService policy = new BotRuntimePolicyService(null);

  @Test void disabledRfqFlowIsBlockedAndCannotCreateDraft() {
    ChannelBotRuntimeConfiguration config = config(c -> c.apply(true, true, true,
        BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.DISABLED, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.DISABLED,
        UnknownCustomerMode.HANDOFF, true, "BOT_REVIEW", 1440, InventoryFreshnessPolicy.WARN_AND_HANDOFF,
        PriceVisibilityPolicy.IDENTIFIED_CUSTOMER_ONLY, "g", "f", "h", NOW));

    BotFlowPolicyDecision decision = policy.decide(config, BotFlow.RFQ, BotRuntimePolicyService.Context.unidentified());

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.mayCreateDraft()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(BotFlowPolicyReason.FLOW_DISABLED);
    assertThat(decision.requiresHandoff()).isTrue();
  }

  @Test void priceVisibilityNeverBlocksPriceAndExposesNoPrice() {
    ChannelBotRuntimeConfiguration config = config(c -> c.apply(true, true, true,
        BotFlowMode.DISABLED, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.DISABLED,
        UnknownCustomerMode.HANDOFF, true, "BOT_REVIEW", 1440, InventoryFreshnessPolicy.WARN_AND_HANDOFF,
        PriceVisibilityPolicy.NEVER, "g", "f", "h", NOW));

    BotFlowPolicyDecision decision = policy.decide(config, BotFlow.PRICE, BotRuntimePolicyService.Context.unidentified());

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.mayExposePrice()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(BotFlowPolicyReason.PRICE_VISIBILITY_NEVER);
  }

  @Test void unknownCustomerHandoffRoutesPriceToSafeHandoff() {
    ChannelBotRuntimeConfiguration config = config(c -> c.apply(true, true, true,
        BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.DISABLED,
        UnknownCustomerMode.HANDOFF, true, "BOT_REVIEW", 1440, InventoryFreshnessPolicy.WARN_AND_HANDOFF,
        PriceVisibilityPolicy.IDENTIFIED_CUSTOMER_ONLY, "g", "f", "h", NOW));

    BotFlowPolicyDecision decision = policy.decide(config, BotFlow.PRICE, new BotRuntimePolicyService.Context(false));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.requiresHandoff()).isTrue();
    assertThat(decision.mayExposePrice()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(BotFlowPolicyReason.UNKNOWN_CUSTOMER_HANDOFF);
  }

  @Test void enabledRfqOperatorReviewIsAllowedAsReviewableDraft() {
    ChannelBotRuntimeConfiguration config = config(c -> c.apply(true, true, true,
        BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.DISABLED,
        UnknownCustomerMode.HANDOFF, true, "BOT_REVIEW", 1440, InventoryFreshnessPolicy.WARN_AND_HANDOFF,
        PriceVisibilityPolicy.IDENTIFIED_CUSTOMER_ONLY, "g", "f", "h", NOW));

    BotFlowPolicyDecision decision = policy.decide(config, BotFlow.RFQ, BotRuntimePolicyService.Context.unidentified());

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.mayCreateDraft()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(BotFlowPolicyReason.ALLOWED_OPERATOR_REVIEW_ONLY);
  }

  @Test void wholeBotDisabledBlocksEveryFlow() {
    ChannelBotRuntimeConfiguration config = config(c -> c.apply(false, true, true,
        BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.DISABLED,
        UnknownCustomerMode.HANDOFF, true, "BOT_REVIEW", 1440, InventoryFreshnessPolicy.WARN_AND_HANDOFF,
        PriceVisibilityPolicy.IDENTIFIED_CUSTOMER_ONLY, "g", "f", "h", NOW));

    BotFlowPolicyDecision decision = policy.decide(config, BotFlow.GREETING, BotRuntimePolicyService.Context.unidentified());

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(BotFlowPolicyReason.BOT_DISABLED);
  }

  private ChannelBotRuntimeConfiguration config(java.util.function.Consumer<ChannelBotRuntimeConfiguration> mutator) {
    ChannelBotRuntimeConfiguration config = new ChannelBotRuntimeConfiguration(UUID.randomUUID(), UUID.randomUUID(), NOW);
    mutator.accept(config);
    return config;
  }
}
