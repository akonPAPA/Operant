package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-19 Layer B — Advisory AI Memory Retrieval DTOs.
 *
 * Bounded request/response shapes for tenant-scoped advisory memory retrieval. The request never carries a
 * tenant id (tenant is resolved from {@code TenantContext}); responses expose only already-sanitized,
 * bounded memory metadata/summaries — never raw documents/prompts/messages, secrets, or internal value
 * hashes. Every hint is {@code advisoryOnly = true}.
 */
public final class AiAdvisoryMemoryDtos {
  private AiAdvisoryMemoryDtos() {}

  /**
   * Advisory retrieval request body. {@code taskType} selects the relevant namespaces; {@code namespaces}
   * optionally narrows them. Superseded/invalidated memory is excluded by default. {@code maxResults} is
   * clamped server-side (1..25).
   */
  public record AdvisoryMemoryRetrievalRequest(
      String taskType,
      List<String> namespaces,
      List<String> sourceTypes,
      String subjectType,
      UUID subjectId,
      String lookupKey,
      Integer maxResults,
      BigDecimal minConfidence,
      Boolean includeSuperseded,
      Boolean includeInvalidated) {}

  /**
   * A single ranked advisory hint. Carries only bounded, sanitized memory metadata and the deterministic
   * score plus its reason codes. {@code advisoryOnly} is always {@code true}.
   */
  public record AdvisoryMemoryHintDto(
      UUID memoryRecordId,
      String namespace,
      String memoryKey,
      String sourceType,
      UUID sourceId,
      String authority,
      BigDecimal confidence,
      int score,
      List<String> reasonCodes,
      String summary,
      Instant createdAt,
      Instant expiresAt,
      boolean advisoryOnly) {}

  /** Bounded advisory retrieval response. */
  public record AdvisoryMemoryRetrievalResponse(
      String taskType,
      List<String> namespaces,
      int requestedMaxResults,
      int returnedCount,
      boolean advisoryOnly,
      List<AdvisoryMemoryHintDto> hints) {}
}
