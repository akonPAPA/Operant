package com.orderpilot.domain.journey;

/** OP-CAP-22 — the trusted internal source object a journey is derived from. */
public enum JourneySourceType {
  DRAFT_QUOTE,
  DRAFT_ORDER,
  ORDER,
  VALIDATION_REVIEW,
  RECONCILIATION_CASE,
  EXTERNAL_MIRROR
}
