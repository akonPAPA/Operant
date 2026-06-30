package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-21 — Transaction Command Center read models.
 *
 * <p>Read-only, tenant-scoped projection DTOs that power the Operant command center surface. All
 * shapes are derived from existing domain tables/services; the source of truth remains those tables.
 *
 * <p>Safety: these DTOs never carry secrets, raw AI prompts, raw document/message payloads, audit
 * metadata blobs, or raw payment-sensitive data. Lists are bounded (preview windows). Each section
 * carries {@code available}/{@code partial} flags so the frontend can render honest "not connected
 * yet" / "no data yet" states instead of fabricating values.
 */
public final class CommandCenterDtos {
  private CommandCenterDtos() {}

  public record CommandCenterSummaryDto(
      List<CommandCenterMetricDto> metrics,
      WorkQueuePreviewDto workQueue,
      RuntimeHealthDto runtime,
      OutboxHealthDto outbox,
      AuditTimelinePreviewDto auditTimeline,
      ReconciliationPreviewDto reconciliation,
      Instant generatedAt) {}

  /**
   * A single command-center KPI. {@code available=false} means the metric has no real backing data
   * source yet (the frontend must show "unavailable", never a fabricated number). {@code partial}
   * means the value is a bounded sample / approximation rather than an exact total.
   */
  public record CommandCenterMetricDto(
      String key,
      String label,
      long value,
      String unit,
      boolean available,
      boolean partial,
      String note) {

    public static CommandCenterMetricDto count(String key, String label, long value) {
      return new CommandCenterMetricDto(key, label, value, "COUNT", true, false, null);
    }

    public static CommandCenterMetricDto unavailable(String key, String label, String note) {
      return new CommandCenterMetricDto(key, label, 0L, "COUNT", false, true, note);
    }
  }

  public record WorkQueuePreviewDto(
      List<WorkQueueItemDto> items,
      long openTotal,
      int previewLimit,
      boolean partial,
      Instant generatedAt) {}

  /** A bounded preview row for a pending operator review case. No raw summary/payload text. */
  public record WorkQueueItemDto(
      UUID caseId,
      String caseNumber,
      String title,
      String status,
      String severity,
      String priority,
      String sourceType,
      Instant createdAt,
      String linkRoute) {}

  public record RuntimeHealthDto(
      boolean available,
      long pendingJobs,
      long runningJobs,
      long failedJobs,
      Instant lastJobQueuedAt,
      boolean degraded,
      String note) {}

  public record OutboxHealthDto(
      boolean available,
      long pendingEvents,
      long publishedEvents,
      long skippedExternalDisabled,
      Instant lastPublishedAt,
      boolean degraded,
      String note) {}

  public record AuditTimelinePreviewDto(
      List<AuditTimelineItemDto> items,
      int previewLimit,
      boolean partial,
      Instant generatedAt) {}

  /** A bounded audit row. Carries identifiers + action only — never the audit metadata JSON blob. */
  public record AuditTimelineItemDto(
      String action,
      String entityType,
      String entityId,
      Instant occurredAt) {}

  public record ReconciliationPreviewDto(
      boolean available,
      long openCases,
      long highSeverityOpenCases,
      List<ReconciliationPreviewCaseDto> recentCases,
      int previewLimit,
      boolean partial,
      String note,
      Instant generatedAt) {}

  public record ReconciliationPreviewCaseDto(
      UUID caseId,
      UUID productId,
      UUID locationId,
      BigDecimal mismatchQuantity,
      String severity,
      String status,
      Instant updatedAt) {}
}
