package com.orderpilot.domain.trust.events;

/**
 * OP-CAP-18 Trust/AI Event Projector Runtime.
 *
 * Lifecycle status of a {@link TrustAiDomainEvent}. {@code PENDING}/{@code FAILED} are eligible for
 * (re)processing; {@code PROCESSED}/{@code SKIPPED}/{@code DEAD_LETTERED} are terminal. {@code FAILED}
 * events retry until the retry cap, after which they are {@code DEAD_LETTERED} (never infinite-looped).
 */
public enum TrustAiEventStatus {
  PENDING,
  PROCESSING,
  PROCESSED,
  FAILED,
  SKIPPED,
  DEAD_LETTERED
}
