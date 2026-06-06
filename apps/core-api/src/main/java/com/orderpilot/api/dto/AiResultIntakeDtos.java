package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OP-CAP-07D — Core API intake contract for AI-worker (OP-CAP-07C) processing results.
 *
 * <p>This is the wire contract for untrusted advisory AI output. {@code tenantRef} is carried for
 * correlation only and is NOT treated as authority — the trusted tenant is resolved server-side from
 * {@code TenantContext}. {@code extractionResult} / {@code providerMetadata} are free-form advisory
 * maps; they are bounds-checked and screened for forbidden top-level action keys before persistence.
 */
public final class AiResultIntakeDtos {
  private AiResultIntakeDtos() {}

  public record AiProcessingResultIntakeRequest(
      UUID jobId,
      String tenantRef,
      String sourceType,
      UUID sourceId,
      String status,
      Map<String, Object> extractionResult,
      List<String> warnings,
      List<String> errors,
      List<String> promptInjectionSignals,
      Map<String, Object> providerMetadata,
      String schemaVersion,
      Instant startedAt,
      Instant completedAt,
      Long durationMs,
      String safeFailureReason) {}

  public record AiProcessingResultIntakeResponse(
      UUID processingJobId,
      UUID extractionRunId,
      UUID extractionResultId,
      String jobStatus,
      String resultStatus,
      boolean duplicate,
      boolean advisoryOnly,
      Instant recordedAt) {}
}
