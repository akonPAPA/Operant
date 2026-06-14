package com.orderpilot.domain.journey.events;

/**
 * OP-CAP-23 Event/Outbox-driven Order Journey Projector Runtime.
 *
 * <p>The narrow, safe set of internal event types that can trigger an Order Journey projection refresh.
 * Each event carries its own {@code sourceType}/{@code sourceId} (a trusted internal source row) — the type
 * here is the business reason for the refresh, used for idempotency and observability. No payment-provider,
 * carrier, ERP, GPS, or AI-authored event types exist here by design.
 */
public enum JourneyProjectionEventType {
  /** A draft quote was created/updated and its journey should be (re)projected. */
  DRAFT_QUOTE_CREATED,
  /** A draft order was created/updated and its journey should be (re)projected. */
  DRAFT_ORDER_CREATED,
  /** A validation review case was registered and its journey should be (re)projected. */
  VALIDATION_REVIEW_REGISTERED,
  /** A reconciliation case was created/updated and its journey should be (re)projected. */
  RECONCILIATION_CASE_UPDATED,
  /** An internal fulfillment signal was recorded; the journey projection should refresh. */
  FULFILLMENT_SIGNAL_RECORDED,
  /** An explicit, operator/system-requested projection refresh for a known source. */
  ORDER_JOURNEY_REFRESH_REQUESTED
}
