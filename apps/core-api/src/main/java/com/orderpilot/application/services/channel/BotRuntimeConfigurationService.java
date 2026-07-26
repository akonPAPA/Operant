package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.BotRuntimeConfigurationDtos.BotRuntimeConfigurationListItem;
import com.orderpilot.api.dto.BotRuntimeConfigurationDtos.BotRuntimeConfigurationResponse;
import com.orderpilot.api.dto.BotRuntimeConfigurationDtos.BotRuntimeConfigurationUpdateRequest;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.bot.BotFlowMode;
import com.orderpilot.domain.bot.InventoryFreshnessPolicy;
import com.orderpilot.domain.bot.PriceVisibilityPolicy;
import com.orderpilot.domain.bot.UnknownCustomerMode;
import com.orderpilot.domain.channel.ChannelBotRuntimeConfiguration;
import com.orderpilot.domain.channel.ChannelBotRuntimeConfigurationRepository;
import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelConnectionRepository;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-06B Controlled Bot Runtime Configuration command/query service.
 *
 * <p>Owns safe-default creation, validated updates, and reset of tenant-scoped, per-connection bot
 * runtime configuration. All operations resolve the tenant from {@link TenantContext} and verify
 * connection ownership; the request body is never trusted for tenant authority. The service never
 * returns or audits tokens, secret references, or provider credentials.
 */
@Service
public class BotRuntimeConfigurationService {
  private static final String EXTERNAL_EXECUTION = "DISABLED";
  private static final int MIN_FRESHNESS_MINUTES = 1;
  private static final int MAX_FRESHNESS_MINUTES = 10080; // 7 days
  private static final int MAX_TEMPLATE_LENGTH = 500;
  // Bot-capable providers eligible for runtime configuration. Telegram is wired in OP-CAP-06A/06B;
  // the rest are listed as configurable-but-intake-only and constrained by the bridge.
  private static final Set<ChannelProviderType> BOT_CAPABLE = Set.of(
      ChannelProviderType.TELEGRAM, ChannelProviderType.WHATSAPP, ChannelProviderType.META_MESSENGER,
      ChannelProviderType.VIBER, ChannelProviderType.WECHAT);

  private final ChannelBotRuntimeConfigurationRepository configurationRepository;
  private final ChannelConnectionRepository connectionRepository;
  private final AuditEventService auditEventService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public BotRuntimeConfigurationService(
      ChannelBotRuntimeConfigurationRepository configurationRepository,
      ChannelConnectionRepository connectionRepository,
      AuditEventService auditEventService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.configurationRepository = configurationRepository;
    this.connectionRepository = connectionRepository;
    this.auditEventService = auditEventService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /** Connections eligible for bot runtime configuration in this tenant, with config status. */
  @Transactional(readOnly = true)
  public List<BotRuntimeConfigurationListItem> listEligible() {
    UUID tenantId = TenantContext.requireTenantId();
    return connectionRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
        .filter(c -> BOT_CAPABLE.contains(c.getProviderType()))
        .map(c -> {
          Optional<ChannelBotRuntimeConfiguration> config =
              configurationRepository.findByTenantIdAndChannelConnectionId(tenantId, c.getId());
          return new BotRuntimeConfigurationListItem(
              c.getId(), c.getProviderType().name(), c.getDisplayName(), c.getStatus(),
              c.getWebhookVerificationMode(), config.isPresent(),
              config.map(ChannelBotRuntimeConfiguration::isEnabled).orElse(false),
              config.map(ChannelBotRuntimeConfiguration::getUpdatedAt).orElse(null));
        })
        .toList();
  }

  /** Admin read: returns existing config or a freshly created safe default for the connection. */
  @Transactional
  public BotRuntimeConfigurationResponse getForConnection(UUID connectionId) {
    ChannelConnection connection = requireConnection(connectionId);
    return toResponse(getOrCreateDefault(connection), connection);
  }

  @Transactional
  public ChannelBotRuntimeConfiguration getOrCreateDefaultForConnection(UUID connectionId) {
    return getOrCreateDefault(requireConnection(connectionId));
  }

  @Transactional
  public BotRuntimeConfigurationResponse updateForConnection(UUID connectionId, BotRuntimeConfigurationUpdateRequest request) {
    ChannelConnection connection = requireConnection(connectionId);
    ChannelBotRuntimeConfiguration config = getOrCreateDefault(connection);

    boolean enabled = orDefault(request.enabled(), config.isEnabled());
    boolean greeting = orDefault(request.greetingEnabled(), config.isGreetingEnabled());
    boolean availability = orDefault(request.availabilityCheckEnabled(), config.isAvailabilityCheckEnabled());
    BotFlowMode priceMode = parseMode(request.priceCheckMode(), config.getPriceCheckMode(), "priceCheckMode");
    BotFlowMode rfqMode = parseMode(request.rfqCaptureMode(), config.getRfqCaptureMode(), "rfqCaptureMode");
    BotFlowMode substituteMode = parseMode(request.substituteSuggestionMode(), config.getSubstituteSuggestionMode(), "substituteSuggestionMode");
    BotFlowMode orderStatusMode = parseMode(request.orderStatusMode(), config.getOrderStatusMode(), "orderStatusMode");
    UnknownCustomerMode unknownCustomerMode = parseUnknown(request.unknownCustomerMode(), config.getUnknownCustomerMode());
    boolean humanHandoff = orDefault(request.humanHandoffEnabled(), config.isHumanHandoffEnabled());
    String queue = blankToDefault(request.handoffQueueKey(), config.getHandoffQueueKey());
    int freshness = request.inventoryFreshnessMaxMinutes() == null ? config.getInventoryFreshnessMaxMinutes() : request.inventoryFreshnessMaxMinutes();
    InventoryFreshnessPolicy freshnessPolicy = parseFreshness(request.inventoryFreshnessPolicy(), config.getInventoryFreshnessPolicy());
    PriceVisibilityPolicy priceVisibility = parsePriceVisibility(request.priceVisibilityPolicy(), config.getPriceVisibilityPolicy());
    String greetingTemplate = template(request.safeGreetingTemplate(), config.getSafeGreetingTemplate(), "safeGreetingTemplate");
    String fallbackTemplate = template(request.safeFallbackTemplate(), config.getSafeFallbackTemplate(), "safeFallbackTemplate");
    String handoffTemplate = template(request.handoffTemplate(), config.getHandoffTemplate(), "handoffTemplate");

    validateCombinations(priceMode, priceVisibility, rfqMode, substituteMode, orderStatusMode, humanHandoff, unknownCustomerMode, freshness);

    BotFlowMode oldPriceMode = config.getPriceCheckMode();
    BotFlowMode oldRfqMode = config.getRfqCaptureMode();
    boolean oldHandoff = config.isHumanHandoffEnabled();

    config.apply(enabled, greeting, availability, priceMode, rfqMode, substituteMode, orderStatusMode,
        unknownCustomerMode, humanHandoff, queue, freshness, freshnessPolicy, priceVisibility,
        greetingTemplate, fallbackTemplate, handoffTemplate, clock.instant());
    ChannelBotRuntimeConfiguration saved = configurationRepository.save(config);

    Map<String, Object> metadata = baseMetadata(saved, connection);
    metadata.put("priceCheckModeFrom", oldPriceMode.name());
    metadata.put("priceCheckModeTo", saved.getPriceCheckMode().name());
    metadata.put("rfqCaptureModeFrom", oldRfqMode.name());
    metadata.put("rfqCaptureModeTo", saved.getRfqCaptureMode().name());
    metadata.put("humanHandoffEnabledFrom", oldHandoff);
    metadata.put("humanHandoffEnabledTo", saved.isHumanHandoffEnabled());
    audit("BOT_RUNTIME_CONFIG_UPDATED", saved, metadata);
    return toResponse(saved, connection);
  }

  @Transactional
  public BotRuntimeConfigurationResponse resetDefaults(UUID connectionId) {
    ChannelConnection connection = requireConnection(connectionId);
    ChannelBotRuntimeConfiguration config = getOrCreateDefault(connection);
    applySafeDefault(config);
    ChannelBotRuntimeConfiguration saved = configurationRepository.save(config);
    audit("BOT_RUNTIME_CONFIG_RESET_DEFAULTS", saved, baseMetadata(saved, connection));
    return toResponse(saved, connection);
  }

  // --- internal helpers -------------------------------------------------------------------------

  private ChannelBotRuntimeConfiguration getOrCreateDefault(ChannelConnection connection) {
    UUID tenantId = connection.getTenantId();
    return configurationRepository.findByTenantIdAndChannelConnectionId(tenantId, connection.getId())
        .orElseGet(() -> {
          ChannelBotRuntimeConfiguration config = new ChannelBotRuntimeConfiguration(tenantId, connection.getId(), clock.instant());
          applySafeDefault(config);
          ChannelBotRuntimeConfiguration saved = configurationRepository.save(config);
          audit("BOT_RUNTIME_CONFIG_DEFAULT_CREATED", saved, baseMetadata(saved, connection));
          return saved;
        });
  }

  private void applySafeDefault(ChannelBotRuntimeConfiguration config) {
    config.apply(
        true, true, true,
        BotFlowMode.OPERATOR_REVIEW_ONLY,   // price: allowed but review-only by default
        BotFlowMode.OPERATOR_REVIEW_ONLY,   // rfq: review-only (preserves OP-CAP-06A behavior)
        BotFlowMode.OPERATOR_REVIEW_ONLY,   // substitute: review-only
        BotFlowMode.DISABLED,               // order status: disabled by default
        UnknownCustomerMode.HANDOFF,
        true,
        "BOT_REVIEW",
        1440,
        InventoryFreshnessPolicy.WARN_AND_HANDOFF,
        PriceVisibilityPolicy.IDENTIFIED_CUSTOMER_ONLY,
        "Hello. Send a part number, quantity, or RFQ and we will route it through operator-controlled OrderPilot workflows.",
        "I cannot handle that automatically. I routed it to a human operator.",
        "I routed this conversation to a human operator who will follow up.",
        clock.instant());
  }

  private void validateCombinations(BotFlowMode priceMode, PriceVisibilityPolicy priceVisibility, BotFlowMode rfqMode,
      BotFlowMode substituteMode, BotFlowMode orderStatusMode, boolean humanHandoff, UnknownCustomerMode unknownCustomerMode, int freshness) {
    if (priceMode == BotFlowMode.DISABLED && priceVisibility != PriceVisibilityPolicy.NEVER) {
      throw new IllegalArgumentException("priceVisibilityPolicy must be NEVER when priceCheckMode is DISABLED");
    }
    if (priceMode == BotFlowMode.CONTROLLED_RESPONSE && priceVisibility == PriceVisibilityPolicy.NEVER) {
      throw new IllegalArgumentException("priceCheckMode CONTROLLED_RESPONSE requires a price visibility policy that allows disclosure to identified/authorized customers");
    }
    if (priceMode == BotFlowMode.CONTROLLED_RESPONSE && unknownCustomerMode == UnknownCustomerMode.SAFE_GENERIC_REPLY) {
      throw new IllegalArgumentException("unknownCustomerMode SAFE_GENERIC_REPLY cannot be combined with priceCheckMode CONTROLLED_RESPONSE; unknown customers must never see price");
    }
    if (!humanHandoff && requiresReview(priceMode, rfqMode, substituteMode, orderStatusMode)) {
      throw new IllegalArgumentException("humanHandoffEnabled must be true while any flow is in OPERATOR_REVIEW_ONLY mode");
    }
    if (freshness < MIN_FRESHNESS_MINUTES || freshness > MAX_FRESHNESS_MINUTES) {
      throw new IllegalArgumentException("inventoryFreshnessMaxMinutes must be between " + MIN_FRESHNESS_MINUTES + " and " + MAX_FRESHNESS_MINUTES);
    }
  }

  private boolean requiresReview(BotFlowMode... modes) {
    for (BotFlowMode mode : modes) {
      if (mode == BotFlowMode.OPERATOR_REVIEW_ONLY) {
        return true;
      }
    }
    return false;
  }

  private ChannelConnection requireConnection(UUID connectionId) {
    UUID tenantId = TenantContext.requireTenantId();
    ChannelConnection connection = connectionRepository.findByIdAndTenantId(connectionId, tenantId)
        .orElseThrow(() -> new NotFoundException("Channel connection not found"));
    if (!BOT_CAPABLE.contains(connection.getProviderType())) {
      throw new IllegalArgumentException("Channel provider " + connection.getProviderType() + " is not eligible for bot runtime configuration");
    }
    return connection;
  }

  private static boolean orDefault(Boolean value, boolean fallback) {
    return value == null ? fallback : value;
  }

  private static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private BotFlowMode parseMode(String value, BotFlowMode fallback, String field) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return BotFlowMode.valueOf(value.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid " + field + " value: " + value);
    }
  }

  private UnknownCustomerMode parseUnknown(String value, UnknownCustomerMode fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return UnknownCustomerMode.valueOf(value.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid unknownCustomerMode value: " + value);
    }
  }

  private InventoryFreshnessPolicy parseFreshness(String value, InventoryFreshnessPolicy fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return InventoryFreshnessPolicy.valueOf(value.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid inventoryFreshnessPolicy value: " + value);
    }
  }

  private PriceVisibilityPolicy parsePriceVisibility(String value, PriceVisibilityPolicy fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return PriceVisibilityPolicy.valueOf(value.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid priceVisibilityPolicy value: " + value);
    }
  }

  private String template(String value, String fallback, String field) {
    if (value == null) {
      return fallback;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be empty");
    }
    if (trimmed.length() > MAX_TEMPLATE_LENGTH) {
      throw new IllegalArgumentException(field + " must be at most " + MAX_TEMPLATE_LENGTH + " characters");
    }
    if (trimmed.indexOf('<') >= 0 || trimmed.indexOf('>') >= 0) {
      throw new IllegalArgumentException(field + " must not contain raw HTML angle brackets");
    }
    return trimmed;
  }

  private BotRuntimeConfigurationResponse toResponse(ChannelBotRuntimeConfiguration c, ChannelConnection connection) {
    return new BotRuntimeConfigurationResponse(
        c.getId(), c.getChannelConnectionId(), connection.getProviderType().name(), connection.getStatus(),
        connection.getWebhookVerificationMode(), c.isEnabled(), c.isGreetingEnabled(), c.isAvailabilityCheckEnabled(),
        c.getPriceCheckMode().name(), c.getRfqCaptureMode().name(), c.getSubstituteSuggestionMode().name(),
        c.getOrderStatusMode().name(), c.getUnknownCustomerMode().name(), c.isHumanHandoffEnabled(), c.getHandoffQueueKey(),
        c.getInventoryFreshnessMaxMinutes(), c.getInventoryFreshnessPolicy().name(), c.getPriceVisibilityPolicy().name(),
        c.getSafeGreetingTemplate(), c.getSafeFallbackTemplate(), c.getHandoffTemplate(), c.getRevision(),
        EXTERNAL_EXECUTION, c.getCreatedAt(), c.getUpdatedAt());
  }

  private Map<String, Object> baseMetadata(ChannelBotRuntimeConfiguration config, ChannelConnection connection) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("configId", config.getId().toString());
    metadata.put("channelConnectionId", connection.getId().toString());
    metadata.put("providerType", connection.getProviderType().name());
    metadata.put("enabled", config.isEnabled());
    metadata.put("revision", config.getRevision());
    metadata.put("externalExecution", EXTERNAL_EXECUTION);
    return metadata;
  }

  private void audit(String action, ChannelBotRuntimeConfiguration config, Map<String, Object> metadata) {
    auditEventService.record(action, "CHANNEL_BOT_RUNTIME_CONFIGURATION", config.getId().toString(), null, writeJson(metadata));
  }

  private String writeJson(Map<String, Object> metadata) {
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (Exception ex) {
      return "{\"externalExecution\":\"" + EXTERNAL_EXECUTION + "\"}";
    }
  }
}
