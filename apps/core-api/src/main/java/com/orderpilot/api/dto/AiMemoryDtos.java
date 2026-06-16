package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * Bounded, sanitized AI memory / runtime-trace DTOs. They expose only typed, bounded governance fields
 * (namespace, key, type, authority, status, confidence, version, TTL, bounded title/summary/value, source
 * pointers, counters). They NEVER expose raw documents, OCR text, prompts, customer messages, secrets,
 * card data, bank credentials, or full PII. AI memory is advisory and low-authority — never source of
 * truth for orders, quotes, prices, stock, payments, counterparty trust, or approval status.
 */
public final class AiMemoryDtos {
  private AiMemoryDtos() {}

  public record AiMemoryEvidenceRefDto(
      UUID id,
      UUID aiMemoryRecordId,
      String evidenceType,
      String evidenceRef,
      String sourceType,
      UUID sourceId,
      String fieldKey,
      BigDecimal confidence,
      Instant createdAt) {}

  public record AiMemoryRecordDto(
      UUID id,
      String namespace,
      String memoryKey,
      String memoryType,
      String status,
      String authorityLevel,
      String sourceType,
      UUID sourceId,
      String sourceRef,
      String title,
      String summary,
      String normalizedValue,
      BigDecimal confidence,
      int weight,
      int version,
      Instant expiresAt,
      Instant invalidatedAt,
      String invalidationReason,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt,
      Instant lastAccessedAt,
      long accessCount) {}

  public record AiMemoryInvalidationEventDto(
      UUID id,
      UUID aiMemoryRecordId,
      String previousStatus,
      String newStatus,
      String reasonCode,
      String reason,
      String actorType,
      UUID actorId,
      Instant createdAt) {}

  public record AiRuntimeTraceDto(
      UUID id,
      String workloadType,
      String modelProvider,
      String modelName,
      String promptVersion,
      String schemaVersion,
      Integer inputTokenEstimate,
      Integer outputTokenEstimate,
      BigDecimal costUnits,
      String status,
      String failureCode,
      String sourceType,
      UUID sourceId,
      Instant createdAt) {}

  // ----------------------------- requests -----------------------------

  /** Optional bounded evidence pointer supplied when creating a memory record. */
  public record AiMemoryEvidenceRefRequest(
      String evidenceType,
      String evidenceRef,
      String sourceType,
      UUID sourceId,
      String fieldKey,
      BigDecimal confidence) {}

  public record CreateAiMemoryRecordRequest(
      String namespace,
      String memoryKey,
      String memoryType,
      String authorityLevel,
      String sourceType,
      UUID sourceId,
      String sourceRef,
      String title,
      String summary,
      String normalizedValue,
      BigDecimal confidence,
      Integer weight,
      Long ttlSeconds,
      List<AiMemoryEvidenceRefRequest> evidence) {}

  /** Supersedes the targeted record with a new version (bounded content only). */
  public record SupersedeAiMemoryRecordRequest(
      String memoryType,
      String authorityLevel,
      String title,
      String summary,
      String normalizedValue,
      BigDecimal confidence,
      Integer weight,
      Long ttlSeconds,
      String reason) {}

  public record InvalidateAiMemoryRecordRequest(
      String reasonCode,
      String reason) {}

  public record SearchAiMemoryResponse(
      String namespace,
      int count,
      boolean includeExpired,
      boolean includeLowConfidence,
      List<AiMemoryRecordDto> records) {}

  public record RecordAiRuntimeTraceRequest(
      String workloadType,
      String modelProvider,
      String modelName,
      String promptVersion,
      String schemaVersion,
      Integer inputTokenEstimate,
      Integer outputTokenEstimate,
      BigDecimal costUnits,
      String status,
      String failureCode,
      String sourceType,
      UUID sourceId) {}
}
