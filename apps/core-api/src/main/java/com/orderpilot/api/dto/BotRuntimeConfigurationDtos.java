package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-06B Controlled Bot Runtime Configuration DTOs.
 *
 * <p>These DTOs expose deterministic policy only. They never carry tokens, secrets, secret
 * references, provider credentials, raw provider payloads, or executable rules. Templates are
 * response copy only.
 */
public final class BotRuntimeConfigurationDtos {
  private BotRuntimeConfigurationDtos() {}

  /** Full configuration view for one channel connection. */
  public record BotRuntimeConfigurationResponse(
      UUID id,
      UUID channelConnectionId,
      String providerType,
      String connectionStatus,
      String connectionVerificationMode,
      boolean enabled,
      boolean greetingEnabled,
      boolean availabilityCheckEnabled,
      String priceCheckMode,
      String rfqCaptureMode,
      String substituteSuggestionMode,
      String orderStatusMode,
      String unknownCustomerMode,
      boolean humanHandoffEnabled,
      String handoffQueueKey,
      int inventoryFreshnessMaxMinutes,
      String inventoryFreshnessPolicy,
      String priceVisibilityPolicy,
      String safeGreetingTemplate,
      String safeFallbackTemplate,
      String handoffTemplate,
      int revision,
      String externalExecution,
      Instant createdAt,
      Instant updatedAt
  ) {}

  /** Mutable subset an admin may update. No tenant id, no secrets. */
  public record BotRuntimeConfigurationUpdateRequest(
      Boolean enabled,
      Boolean greetingEnabled,
      Boolean availabilityCheckEnabled,
      String priceCheckMode,
      String rfqCaptureMode,
      String substituteSuggestionMode,
      String orderStatusMode,
      String unknownCustomerMode,
      Boolean humanHandoffEnabled,
      String handoffQueueKey,
      Integer inventoryFreshnessMaxMinutes,
      String inventoryFreshnessPolicy,
      String priceVisibilityPolicy,
      String safeGreetingTemplate,
      String safeFallbackTemplate,
      String handoffTemplate
  ) {}

  /** List item: which connections are eligible for bot runtime configuration and their status. */
  public record BotRuntimeConfigurationListItem(
      UUID channelConnectionId,
      String providerType,
      String displayName,
      String connectionStatus,
      String connectionVerificationMode,
      boolean configured,
      boolean enabled,
      Instant updatedAt
  ) {}
}
