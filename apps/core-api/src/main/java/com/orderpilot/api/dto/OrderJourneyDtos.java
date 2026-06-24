package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-22 — read DTOs for the Order Journey & Fulfillment Visibility surface.
 *
 * <p>All shapes are tenant-safe and exclude raw payloads, secrets, raw AI prompts, raw document
 * contents, and payment-sensitive data. Every milestone/event carries an explicit {@code
 * evidenceLevel} (VERIFIED / MIRRORED / SYSTEM_DERIVED / ESTIMATED / MANUAL / UNKNOWN) and a {@code
 * customerVisible} flag so the frontend honestly distinguishes verified vs estimated vs unknown vs
 * blocked, and customer-visible vs internal-only. The customer-safe DTO omits internal-only data.
 */
public final class OrderJourneyDtos {
  private OrderJourneyDtos() {}

  public record OrderJourneyListItemDto(
      UUID id,
      String sourceType,
      UUID sourceId,
      UUID customerAccountId,
      String customerDisplayName,
      String currentStage,
      String currentStatus,
      String riskLevel,
      boolean blocked,
      String evidenceLevel,
      Instant lastSignalAt,
      Instant updatedAt) {}

  public record OrderJourneySummaryDto(
      List<OrderJourneyListItemDto> items,
      long total,
      long blockedCount,
      int previewLimit,
      boolean partial,
      Instant generatedAt) {}

  public record OrderJourneyAttentionSummaryDto(
      List<OrderJourneyListItemDto> items,
      long attentionTotal,
      long blockedCount,
      int previewLimit,
      boolean partial,
      Instant generatedAt) {}

  public record OrderJourneyMilestoneDto(
      String milestoneCode,
      String milestoneLabel,
      String milestoneState,
      String evidenceLevel,
      Instant occurredAt,
      Instant estimatedAt,
      String sourceType,
      String sourceRef,
      boolean customerVisible,
      int sortOrder) {}

  public record OrderJourneyEventDto(
      String eventType,
      String eventStatus,
      String evidenceLevel,
      String message,
      String sourceType,
      String sourceRef,
      String actorType,
      boolean customerVisible,
      Instant occurredAt) {}

  public record FulfillmentSignalDto(
      UUID id,
      String sourceType,
      String signalType,
      String signalStatus,
      BigDecimal confidence,
      String sourceRef,
      boolean customerVisible,
      Instant receivedAt,
      Instant processedAt) {}

  public record OrderJourneyDetailDto(
      UUID id,
      String sourceType,
      UUID sourceId,
      UUID customerAccountId,
      String customerDisplayName,
      String currentStage,
      String currentStatus,
      String riskLevel,
      boolean blocked,
      String customerVisibleStatus,
      String internalStatus,
      Instant lastSignalAt,
      Instant createdAt,
      Instant updatedAt,
      List<OrderJourneyMilestoneDto> milestones,
      List<OrderJourneyEventDto> recentEvents,
      List<FulfillmentSignalDto> fulfillmentSignals,
      boolean paymentStatusAvailable,
      boolean fulfillmentTrackingConnected,
      // OP-CAP-23: how this projection was obtained — READY (already-projected, the production path) or
      // ON_READ_FALLBACK (materialized during this read as the documented temporary fallback).
      String projectionSource,
      Instant generatedAt) {}

  /**
   * Customer-safe view. Exposes only the customer-visible status, customer-visible milestones, and
   * customer-visible events. Internal status, internal-only milestones/events, risk level, and
   * fulfillment signal internals are intentionally excluded.
   *
   * <p>{@code customerSafeApiPath} is the internal, tenant-scoped, permission-protected API path that
   * serves this customer-safe projection (it still requires ANALYTICS_READ via the route policy and
   * carries the journey id). It is NOT a public, signed, expiring, or non-enumerable secure tracking
   * link — a public buyer tracking gateway is deferred to OP-CAP-46C. Frontends must not present this
   * path to end customers as a shareable public link.
   */
  public record CustomerSafeJourneyDto(
      UUID id,
      String customerVisibleStatus,
      List<OrderJourneyMilestoneDto> milestones,
      List<OrderJourneyEventDto> events,
      boolean fulfillmentTrackingConnected,
      boolean paymentStatusAvailable,
      String customerSafeApiPath,
      Instant generatedAt) {}

  /** Signal-ingest request for the audited operator-only POST. */
  public record RecordFulfillmentSignalRequest(
      String sourceType,
      String signalType,
      String signalStatus,
      BigDecimal confidence,
      String sourceRef,
      String rawPayloadRef,
      Boolean customerVisible) {}

  /**
   * OP-CAP-46B — operator manual milestone request. Accepts only the business intent: the milestone
   * code, an operator-only {@code internalNote}, an optional {@code customerSafeNote}, and the
   * {@code customerVisible} flag.
   *
   * <p>Data boundary: {@code internalNote} is operator-only and can never reach the customer-safe
   * view. {@code customerSafeNote} is surfaced to customers only when {@code customerVisible} is true,
   * and only after sanitization/length-limiting. Backend-owned authority fields (tenant, actor,
   * source, status, risk, approval, audit metadata) are NOT accepted.
   */
  public record RecordManualMilestoneRequest(
      String milestoneCode,
      String internalNote,
      String customerSafeNote,
      Boolean customerVisible) {}
}
