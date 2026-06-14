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
      Instant generatedAt) {}

  /**
   * Customer-safe view. Exposes only the customer-visible status, customer-visible milestones, and
   * customer-visible events. Internal status, internal-only milestones/events, risk level, and
   * fulfillment signal internals are intentionally excluded.
   */
  public record CustomerSafeJourneyDto(
      UUID id,
      String customerVisibleStatus,
      List<OrderJourneyMilestoneDto> milestones,
      List<OrderJourneyEventDto> events,
      boolean fulfillmentTrackingConnected,
      boolean paymentStatusAvailable,
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
}
