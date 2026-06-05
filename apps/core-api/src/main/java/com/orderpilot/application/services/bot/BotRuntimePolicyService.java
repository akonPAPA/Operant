package com.orderpilot.application.services.bot;

import com.orderpilot.domain.bot.BotFlow;
import com.orderpilot.domain.bot.BotFlowMode;
import com.orderpilot.domain.bot.BotFlowPolicyReason;
import com.orderpilot.domain.bot.PriceVisibilityPolicy;
import com.orderpilot.domain.bot.UnknownCustomerMode;
import com.orderpilot.domain.channel.ChannelBotRuntimeConfiguration;
import com.orderpilot.domain.channel.ChannelBotRuntimeConfigurationRepository;
import com.orderpilot.common.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-06B runtime policy resolver.
 *
 * <p>Translates a tenant-scoped {@link ChannelBotRuntimeConfiguration} into deterministic
 * {@link BotFlowPolicyDecision}s consumed by the channel→bot bridge before the controlled runtime
 * is invoked. This component is pure/deterministic and never performs writes or external calls.
 *
 * <p>Safety contract: a decision may only restrict the runtime relative to OP-CAP-06A defaults.
 * It can never enable price disclosure, drafts, or responses that the runtime did not already
 * support, and it never enables outbound sends or external execution.
 */
@Service
public class BotRuntimePolicyService {
  private final ChannelBotRuntimeConfigurationRepository configurationRepository;

  public BotRuntimePolicyService(ChannelBotRuntimeConfigurationRepository configurationRepository) {
    this.configurationRepository = configurationRepository;
  }

  /** Context the bridge can determine without invoking the runtime. */
  /**
   * Identity-aware decision context (OP-CAP-06C). {@code customerIdentified} is true only for a
   * confirmed (RESOLVED) identity. {@code identityAmbiguous} marks an unconfirmed candidate, and
   * {@code identityBlocked} marks an explicitly blocked sender. Identity only adds context; it never
   * relaxes configuration or runtime validation.
   */
  public record Context(boolean customerIdentified, boolean identityAmbiguous, boolean identityBlocked) {
    /** Backward-compatible constructor: identity unknown/ambiguous flags default to false. */
    public Context(boolean customerIdentified) {
      this(customerIdentified, false, false);
    }

    public static Context unidentified() {
      return new Context(false, false, false);
    }

    public static Context identified() {
      return new Context(true, false, false);
    }

    public static Context ambiguous() {
      return new Context(false, true, false);
    }

    public static Context blocked() {
      return new Context(false, false, true);
    }
  }

  @Transactional(readOnly = true)
  public Optional<ChannelBotRuntimeConfiguration> resolvePolicy(UUID connectionId) {
    return configurationRepository.findByTenantIdAndChannelConnectionId(TenantContext.requireTenantId(), connectionId);
  }

  /** Decide whether the resolved flow may run for the given configuration and context. */
  public BotFlowPolicyDecision decide(ChannelBotRuntimeConfiguration config, BotFlow flow, Context context) {
    UUID configId = config.getId();
    if (!config.isEnabled()) {
      return BotFlowPolicyDecision.blocked(flow, BotFlowPolicyReason.BOT_DISABLED,
          "The bot is disabled for this connection. Routed to operator review.", configId);
    }
    // OP-CAP-06C: a blocked sender never receives a business answer (defense-in-depth; the bridge
    // also short-circuits before reaching policy/runtime).
    if (context.identityBlocked()) {
      return BotFlowPolicyDecision.blocked(flow, BotFlowPolicyReason.IDENTITY_BLOCKED,
          "This sender is blocked. No bot business response was produced.", configId);
    }
    return switch (flow) {
      case GREETING -> config.isGreetingEnabled()
          ? allow(flow, BotFlowMode.CONTROLLED_RESPONSE, BotFlowPolicyReason.ALLOWED_CONTROLLED_RESPONSE, false, false, false, false, configId)
          : BotFlowPolicyDecision.blocked(flow, BotFlowPolicyReason.FLOW_DISABLED, fallback(config), configId);
      case HUMAN_HANDOFF -> allow(flow, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowPolicyReason.ALLOWED_OPERATOR_REVIEW_ONLY, true, false, false, false, configId);
      case AVAILABILITY -> config.isAvailabilityCheckEnabled()
          ? allow(flow, BotFlowMode.CONTROLLED_RESPONSE, BotFlowPolicyReason.ALLOWED_CONTROLLED_RESPONSE, false, false, true, false, configId)
          : BotFlowPolicyDecision.blocked(flow, BotFlowPolicyReason.FLOW_DISABLED, fallback(config), configId);
      case PRICE -> decidePrice(config, context, configId);
      case RFQ -> decideMode(flow, config.getRfqCaptureMode(), config, false, false, true, configId);
      case SUBSTITUTE -> decideMode(flow, config.getSubstituteSuggestionMode(), config, false, false, false, configId);
      case ORDER_STATUS -> decideStatus(config, context, configId);
      case UNKNOWN -> allow(flow, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowPolicyReason.ALLOWED_OPERATOR_REVIEW_ONLY, true, false, false, false, configId);
    };
  }

  private BotFlowPolicyDecision decidePrice(ChannelBotRuntimeConfiguration config, Context context, UUID configId) {
    if (config.getPriceCheckMode() == BotFlowMode.DISABLED || config.getPriceVisibilityPolicy() == PriceVisibilityPolicy.NEVER) {
      return BotFlowPolicyDecision.blocked(BotFlow.PRICE, BotFlowPolicyReason.PRICE_VISIBILITY_NEVER,
          "Price is not disclosed by the bot. Routed to operator review.", configId);
    }
    if (!context.customerIdentified()) {
      return unknownCustomer(BotFlow.PRICE, config, context, configId);
    }
    return switch (config.getPriceCheckMode()) {
      case OPERATOR_REVIEW_ONLY -> allow(BotFlow.PRICE, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowPolicyReason.ALLOWED_OPERATOR_REVIEW_ONLY, true, false, false, false, configId);
      case CONTROLLED_RESPONSE -> allow(BotFlow.PRICE, BotFlowMode.CONTROLLED_RESPONSE, BotFlowPolicyReason.ALLOWED_CONTROLLED_RESPONSE, false, true, false, false, configId);
      default -> allow(BotFlow.PRICE, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowPolicyReason.ALLOWED_OPERATOR_REVIEW_ONLY, true, false, false, false, configId);
    };
  }

  private BotFlowPolicyDecision decideStatus(ChannelBotRuntimeConfiguration config, Context context, UUID configId) {
    if (config.getOrderStatusMode() == BotFlowMode.DISABLED) {
      return BotFlowPolicyDecision.blocked(BotFlow.ORDER_STATUS, BotFlowPolicyReason.FLOW_DISABLED, fallback(config), configId);
    }
    if (!context.customerIdentified()) {
      return unknownCustomer(BotFlow.ORDER_STATUS, config, context, configId);
    }
    return allow(BotFlow.ORDER_STATUS, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowPolicyReason.ALLOWED_OPERATOR_REVIEW_ONLY, true, false, false, false, configId);
  }

  private BotFlowPolicyDecision decideMode(BotFlow flow, BotFlowMode mode, ChannelBotRuntimeConfiguration config,
      boolean mayExposePrice, boolean mayExposeAvailability, boolean draftCapableFlow, UUID configId) {
    return switch (mode) {
      case DISABLED -> BotFlowPolicyDecision.blocked(flow, BotFlowPolicyReason.FLOW_DISABLED, fallback(config), configId);
      case OPERATOR_REVIEW_ONLY -> allow(flow, mode, BotFlowPolicyReason.ALLOWED_OPERATOR_REVIEW_ONLY, true, mayExposePrice, mayExposeAvailability, draftCapableFlow, configId);
      case CONTROLLED_DRAFT -> allow(flow, mode, BotFlowPolicyReason.ALLOWED_CONTROLLED_DRAFT, true, mayExposePrice, mayExposeAvailability, draftCapableFlow, configId);
      case CONTROLLED_RESPONSE -> allow(flow, mode, BotFlowPolicyReason.ALLOWED_CONTROLLED_RESPONSE, false, mayExposePrice, mayExposeAvailability, draftCapableFlow, configId);
    };
  }

  private BotFlowPolicyDecision unknownCustomer(BotFlow flow, ChannelBotRuntimeConfiguration config, Context context, UUID configId) {
    // OP-CAP-06C: an unconfirmed candidate (ambiguous) always routes to operator review, never reject.
    if (context.identityAmbiguous()) {
      return new BotFlowPolicyDecision(flow, false, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowPolicyReason.AMBIGUOUS_CUSTOMER_HANDOFF, true, false, false, false,
          "Customer identity is an unconfirmed candidate. Routed to operator review.", configId);
    }
    UnknownCustomerMode mode = config.getUnknownCustomerMode();
    if (mode == UnknownCustomerMode.REJECT) {
      return new BotFlowPolicyDecision(flow, false, BotFlowMode.DISABLED, BotFlowPolicyReason.UNKNOWN_CUSTOMER_REJECTED, false, false, false, false,
          "Customer identity could not be verified. Request rejected with audit.", configId);
    }
    return new BotFlowPolicyDecision(flow, false, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowPolicyReason.UNKNOWN_CUSTOMER_HANDOFF, true, false, false, false,
        "Customer identity could not be verified. Routed to operator review.", configId);
  }

  private BotFlowPolicyDecision allow(BotFlow flow, BotFlowMode mode, BotFlowPolicyReason reason,
      boolean requiresHandoff, boolean mayExposePrice, boolean mayExposeAvailability, boolean mayCreateDraft, UUID configId) {
    return new BotFlowPolicyDecision(flow, true, mode, reason, requiresHandoff, mayExposePrice, mayExposeAvailability, mayCreateDraft, null, configId);
  }

  private String fallback(ChannelBotRuntimeConfiguration config) {
    return config.getSafeFallbackTemplate();
  }
}
