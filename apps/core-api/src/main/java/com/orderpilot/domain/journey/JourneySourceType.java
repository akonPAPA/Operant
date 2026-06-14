package com.orderpilot.domain.journey;

/** OP-CAP-22 — the trusted internal source object a journey is derived from. */
public enum JourneySourceType {
  DRAFT_QUOTE,
  DRAFT_ORDER,
  ORDER,
  VALIDATION_REVIEW,
  RECONCILIATION_CASE,
  EXTERNAL_MIRROR;

  /**
   * OP-CAP-23 — whether the event/outbox projector can resolve a durable internal source row for this type
   * and idempotently refresh a journey from it. {@code EXTERNAL_MIRROR} is intentionally NOT projectable
   * this stage: there is no persisted external source entity to resolve (external mirror writes are
   * out of scope), so the projector skips it safely rather than fabricating state.
   */
  public boolean isProjectable() {
    return this != EXTERNAL_MIRROR;
  }
}
