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
   * Customer-safe milestone shape. It intentionally omits operator/internal projection fields such as
   * source type/ref, actor data, sort order, raw payload pointers, audit ids, and implementation flags.
   */
  public record CustomerTrackingMilestoneDto(
      String milestoneCode,
      String milestoneLabel,
      String milestoneState,
      String evidenceLevel,
      Instant occurredAt,
      Instant estimatedAt) {}

  /**
   * Customer-safe event shape. It intentionally omits actor, source/ref, audit, connector, and raw
   * payload details while preserving the safe status message and evidence classification.
   */
  public record CustomerTrackingEventDto(
      String eventType,
      String eventStatus,
      String evidenceLevel,
      String message,
      Instant occurredAt) {}

  /**
   * Customer-safe view. Exposes only the customer-visible status, customer-visible milestones, and
   * customer-visible events. Internal status, internal-only milestones/events, risk level, and
   * fulfillment signal internals are intentionally excluded.
   *
   * <p>{@code customerSafeApiPath} is the internal, tenant-scoped, permission-protected API path that
   * serves this customer-safe projection (it still requires ANALYTICS_READ via the route policy and
   * carries the journey id). It is NOT a public, signed, expiring, or non-enumerable secure tracking
   * link. The shareable public buyer link is the OP-CAP-46C secure tracking link
   * ({@code GET /api/v1/public/order-tracking/{token}}, minted via
   * {@code POST /api/v1/order-journeys/{id}/tracking-links}); frontends must use that — never this
   * internal path — when sharing tracking with an end customer.
   */
  public record CustomerSafeJourneyDto(
      UUID id,
      String customerVisibleStatus,
      List<CustomerTrackingMilestoneDto> milestones,
      List<CustomerTrackingEventDto> events,
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

  /**
   * OP-CAP-46C — operator request to mint a secure tracking link for a journey. Business intent only:
   * an optional time-to-live in hours (clamped server-side). Tenant, actor, journey and every other
   * authority field are resolved by the backend (header tenant, trusted actor, path journey id) — they
   * are NOT accepted from the body.
   */
  public record CreateTrackingLinkRequest(Integer expiresInHours) {}

  /**
   * OP-CAP-46C — one-time creation result. The raw token is surfaced exactly once, embedded in {@code
   * trackingPath}, so the operator can share the link; it is never stored (only its hash is) and never
   * returned again or logged. No tenant id, journey id, token hash, or link id is exposed.
   */
  public record TrackingLinkCreatedDto(String trackingPath, Instant expiresAt) {}

  /**
   * OP-CAP-46G — operator request to revoke a tracking link. Business intent only: an optional,
   * bounded, operator-only {@code reason} (sanitized and length-clamped server-side). The link is
   * identified by the path (journey id + internal link id); the raw token is NEVER accepted. Tenant,
   * actor, and every other authority/state field are resolved by the backend (header tenant, trusted
   * actor) — they are NOT accepted from the body and a body that carries them is ignored.
   */
  public record RevokeTrackingLinkRequest(String reason) {}

  /**
   * OP-CAP-46G — operator-only revocation result. Carries only the resulting {@code status}
   * ("REVOKED") and the {@code revokedAt} timestamp. No tenant id, journey id, link id, token, or
   * token hash is exposed.
   */
  public record TrackingLinkRevokedDto(String status, Instant revokedAt) {}

  /**
   * OP-CAP-46C — a single customer-safe milestone for the public secure tracking view. Deliberately
   * carries ONLY customer-facing fields. Internal fields present on {@link OrderJourneyMilestoneDto}
   * (sourceType, sourceRef, sortOrder, customerVisible) are intentionally absent so they can never be
   * serialized to an unauthenticated buyer.
   */
  public record PublicTrackingMilestoneDto(
      String milestoneLabel,
      String milestoneState,
      String evidenceLevel,
      Instant occurredAt,
      Instant estimatedAt) {}

  /**
   * OP-CAP-46C — the response of the public secure tracking link endpoint. Returns only customer-safe
   * tracking fields: a customer-visible status label, customer-visible milestones, and a connected
   * flag. No id, tenant id, journey id, source/actor/connector/signal/risk/internal-status fields, no
   * audit internals, and no token material are exposed. Scope is proven by the token, so no identifier
   * needs to be echoed back.
   */
  public record PublicOrderTrackingView(
      String statusLabel,
      List<PublicTrackingMilestoneDto> milestones,
      boolean fulfillmentTrackingConnected,
      Instant generatedAt) {}
}
