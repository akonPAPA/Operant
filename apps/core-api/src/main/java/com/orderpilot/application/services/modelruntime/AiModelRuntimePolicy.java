package com.orderpilot.application.services.modelruntime;

import java.time.Duration;

/**
 * Bounded policy for one advisory model role/provider/model tuple.
 *
 * <p>This is configuration metadata only. It does not call a model, write business state, create
 * outbox events, approve ChangeRequests, or execute connectors.
 */
public record AiModelRuntimePolicy(
    AiModelRole role,
    AiModelProviderType providerType,
    String modelId,
    int maxContextTokens,
    int maxOutputTokens,
    Duration timeout,
    boolean sequentialExecution,
    boolean heavyModel,
    boolean enabled) {

  public AiModelRuntimePolicy {
    if (role == null) throw new IllegalArgumentException("model role is required");
    if (providerType == null) throw new IllegalArgumentException("provider type is required");
    if (modelId == null || modelId.isBlank()) throw new IllegalArgumentException("model id is required");
    if (maxContextTokens <= 0) throw new IllegalArgumentException("max context must be positive");
    if (maxOutputTokens <= 0) throw new IllegalArgumentException("max output must be positive");
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    if (enabled && providerType == AiModelProviderType.DISABLED) {
      throw new IllegalArgumentException("disabled provider cannot be enabled");
    }
    if (enabled && providerType == AiModelProviderType.REMOTE_PLACEHOLDER) {
      throw new IllegalArgumentException("remote placeholder is not runnable in this foundation");
    }
  }
}
