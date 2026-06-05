package com.orderpilot.domain.channel;

import com.orderpilot.domain.bot.BotFlowMode;
import com.orderpilot.domain.bot.InventoryFreshnessPolicy;
import com.orderpilot.domain.bot.PriceVisibilityPolicy;
import com.orderpilot.domain.bot.UnknownCustomerMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-06B Controlled Bot Runtime Configuration.
 *
 * <p>Tenant-scoped, per-{@code channel_connection} configuration that constrains what the existing
 * controlled bot runtime ({@code BotRuntimeService}) is allowed to do for a verified messenger
 * connection. It is additive to, and does not replace, the legacy tenant-level {@code BotConnection}
 * allow-list. This record stores deterministic policy only — never tokens, secrets, secret
 * references, provider credentials, raw payloads, or executable rules. Templates are response copy
 * only and are never executed.
 */
@Entity
@Table(name = "channel_bot_runtime_configuration")
public class ChannelBotRuntimeConfiguration {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "channel_connection_id", nullable = false) private UUID channelConnectionId;
  @Column(nullable = false) private boolean enabled;
  @Column(name = "greeting_enabled", nullable = false) private boolean greetingEnabled;
  @Column(name = "availability_check_enabled", nullable = false) private boolean availabilityCheckEnabled;
  @Enumerated(EnumType.STRING) @Column(name = "price_check_mode", nullable = false) private BotFlowMode priceCheckMode;
  @Enumerated(EnumType.STRING) @Column(name = "rfq_capture_mode", nullable = false) private BotFlowMode rfqCaptureMode;
  @Enumerated(EnumType.STRING) @Column(name = "substitute_suggestion_mode", nullable = false) private BotFlowMode substituteSuggestionMode;
  @Enumerated(EnumType.STRING) @Column(name = "order_status_mode", nullable = false) private BotFlowMode orderStatusMode;
  @Enumerated(EnumType.STRING) @Column(name = "unknown_customer_mode", nullable = false) private UnknownCustomerMode unknownCustomerMode;
  @Column(name = "human_handoff_enabled", nullable = false) private boolean humanHandoffEnabled;
  @Column(name = "handoff_queue_key", nullable = false) private String handoffQueueKey;
  @Column(name = "inventory_freshness_max_minutes", nullable = false) private int inventoryFreshnessMaxMinutes;
  @Enumerated(EnumType.STRING) @Column(name = "inventory_freshness_policy", nullable = false) private InventoryFreshnessPolicy inventoryFreshnessPolicy;
  @Enumerated(EnumType.STRING) @Column(name = "price_visibility_policy", nullable = false) private PriceVisibilityPolicy priceVisibilityPolicy;
  @Column(name = "safe_greeting_template", nullable = false, length = 500) private String safeGreetingTemplate;
  @Column(name = "safe_fallback_template", nullable = false, length = 500) private String safeFallbackTemplate;
  @Column(name = "handoff_template", nullable = false, length = 500) private String handoffTemplate;
  @Column(nullable = false) private int revision;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected ChannelBotRuntimeConfiguration() {}

  public ChannelBotRuntimeConfiguration(UUID tenantId, UUID channelConnectionId, Instant now) {
    this.tenantId = tenantId;
    this.channelConnectionId = channelConnectionId;
    this.createdAt = now;
    this.updatedAt = now;
    // First apply(...) sets revision to 1 (default creation); each later apply(...) increments it.
    this.revision = 0;
  }

  /** Apply a validated set of configuration values and bump the revision. */
  public void apply(
      boolean enabled,
      boolean greetingEnabled,
      boolean availabilityCheckEnabled,
      BotFlowMode priceCheckMode,
      BotFlowMode rfqCaptureMode,
      BotFlowMode substituteSuggestionMode,
      BotFlowMode orderStatusMode,
      UnknownCustomerMode unknownCustomerMode,
      boolean humanHandoffEnabled,
      String handoffQueueKey,
      int inventoryFreshnessMaxMinutes,
      InventoryFreshnessPolicy inventoryFreshnessPolicy,
      PriceVisibilityPolicy priceVisibilityPolicy,
      String safeGreetingTemplate,
      String safeFallbackTemplate,
      String handoffTemplate,
      Instant now) {
    this.enabled = enabled;
    this.greetingEnabled = greetingEnabled;
    this.availabilityCheckEnabled = availabilityCheckEnabled;
    this.priceCheckMode = priceCheckMode;
    this.rfqCaptureMode = rfqCaptureMode;
    this.substituteSuggestionMode = substituteSuggestionMode;
    this.orderStatusMode = orderStatusMode;
    this.unknownCustomerMode = unknownCustomerMode;
    this.humanHandoffEnabled = humanHandoffEnabled;
    this.handoffQueueKey = handoffQueueKey;
    this.inventoryFreshnessMaxMinutes = inventoryFreshnessMaxMinutes;
    this.inventoryFreshnessPolicy = inventoryFreshnessPolicy;
    this.priceVisibilityPolicy = priceVisibilityPolicy;
    this.safeGreetingTemplate = safeGreetingTemplate;
    this.safeFallbackTemplate = safeFallbackTemplate;
    this.handoffTemplate = handoffTemplate;
    this.updatedAt = now;
    this.revision = this.revision + 1;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getChannelConnectionId() { return channelConnectionId; }
  public boolean isEnabled() { return enabled; }
  public boolean isGreetingEnabled() { return greetingEnabled; }
  public boolean isAvailabilityCheckEnabled() { return availabilityCheckEnabled; }
  public BotFlowMode getPriceCheckMode() { return priceCheckMode; }
  public BotFlowMode getRfqCaptureMode() { return rfqCaptureMode; }
  public BotFlowMode getSubstituteSuggestionMode() { return substituteSuggestionMode; }
  public BotFlowMode getOrderStatusMode() { return orderStatusMode; }
  public UnknownCustomerMode getUnknownCustomerMode() { return unknownCustomerMode; }
  public boolean isHumanHandoffEnabled() { return humanHandoffEnabled; }
  public String getHandoffQueueKey() { return handoffQueueKey; }
  public int getInventoryFreshnessMaxMinutes() { return inventoryFreshnessMaxMinutes; }
  public InventoryFreshnessPolicy getInventoryFreshnessPolicy() { return inventoryFreshnessPolicy; }
  public PriceVisibilityPolicy getPriceVisibilityPolicy() { return priceVisibilityPolicy; }
  public String getSafeGreetingTemplate() { return safeGreetingTemplate; }
  public String getSafeFallbackTemplate() { return safeFallbackTemplate; }
  public String getHandoffTemplate() { return handoffTemplate; }
  public int getRevision() { return revision; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
