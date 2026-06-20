package com.orderpilot.application.services.modelruntime;

import java.time.Duration;
import java.time.Instant;

/**
 * Safe metadata for one advisory model run.
 *
 * <p>Contains no raw prompt, raw response, secrets, customer text, repository dump, DB dump,
 * idempotency hash, connector credential, or internal business-write authority.
 */
public record AiModelRunMetadata(
    String modelId,
    AiModelRole role,
    AiModelProviderType providerType,
    Instant startedAt,
    Instant finishedAt,
    Duration duration,
    AiModelRunStatus status,
    String failureReason,
    Integer promptEvalCount,
    Integer outputEvalCount) {

  public AiModelRunMetadata {
    if (modelId == null || modelId.isBlank()) throw new IllegalArgumentException("model id is required");
    if (role == null) throw new IllegalArgumentException("model role is required");
    if (providerType == null) throw new IllegalArgumentException("provider type is required");
    if (startedAt == null) throw new IllegalArgumentException("startedAt is required");
    if (status == null) throw new IllegalArgumentException("status is required");
    if (finishedAt != null && finishedAt.isBefore(startedAt)) {
      throw new IllegalArgumentException("finishedAt cannot be before startedAt");
    }
    duration = finishedAt == null ? Duration.ZERO : Duration.between(startedAt, finishedAt);
    failureReason = safeFailureReason(status, failureReason);
  }

  public static AiModelRunMetadata planned(AiModelRuntimePolicy policy, Instant startedAt) {
    return new AiModelRunMetadata(
        policy.modelId(), policy.role(), policy.providerType(), startedAt, null, Duration.ZERO,
        AiModelRunStatus.PLANNED, null, null, null);
  }

  public AiModelRunMetadata failed(Instant finishedAt, String reason) {
    return new AiModelRunMetadata(
        modelId, role, providerType, startedAt, finishedAt, Duration.ZERO, AiModelRunStatus.FAILED,
        reason, promptEvalCount, outputEvalCount);
  }

  private static String safeFailureReason(AiModelRunStatus status, String reason) {
    if (status != AiModelRunStatus.FAILED && status != AiModelRunStatus.REJECTED) return null;
    if (reason == null || reason.isBlank()) return "MODEL_RUN_FAILED";
    String trimmed = reason.trim();
    return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
  }
}
