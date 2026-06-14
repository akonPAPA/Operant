package com.orderpilot.application.services.journey;

import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.events.JourneyProjectionEventType;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEvent;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-23 Event/Outbox-driven Order Journey Projector Runtime — durable internal event publisher.
 *
 * <p>Publishes bounded, sanitized, tenant-scoped journey-projection events for the projector runtime to
 * consume. Publishing is idempotent per (tenant, idempotency key): a duplicate publish returns the existing
 * event rather than creating a new one (tolerates duplicate triggers). Payloads are bounded and contain no
 * raw documents/prompts/messages, payment/bank/card data, secrets, or credentials. The pending batch is
 * always bounded and tenant-scoped, and FAILED events are only re-offered while under the retry cap.
 */
@Service
public class OrderJourneyProjectionPublisher {
  /** Max attempts before an event is dead-lettered (no infinite retry). */
  public static final int MAX_RETRY = 3;
  public static final int DEFAULT_BATCH = 50;
  static final int MAX_BATCH = 200;
  static final int MAX_SUMMARY = 512;
  static final int MAX_REASON = 48;
  static final int MAX_IDEMPOTENCY_KEY = 160;

  private final OrderJourneyProjectionEventRepository events;
  private final Clock clock;

  public OrderJourneyProjectionPublisher(OrderJourneyProjectionEventRepository events, Clock clock) {
    this.events = events;
    this.clock = clock;
  }

  public record PublishCommand(
      UUID tenantId, JourneyProjectionEventType eventType, JourneySourceType sourceType, UUID sourceId,
      String reasonCode, UUID correlationId, UUID causationId, String idempotencyKey, String payloadSummary,
      Instant occurredAt) {}

  /** Idempotent publish: returns the existing event when the (tenant, idempotency key) already exists. */
  @Transactional
  public OrderJourneyProjectionEvent publish(PublishCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    JourneyProjectionEventType eventType = required(cmd.eventType(), "eventType");
    JourneySourceType sourceType = required(cmd.sourceType(), "sourceType");
    String idempotencyKey = requireText(cmd.idempotencyKey(), "idempotencyKey");
    if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
      throw new IllegalArgumentException("idempotencyKey exceeds " + MAX_IDEMPOTENCY_KEY + " characters");
    }
    return events.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey).orElseGet(() -> {
      Instant now = clock.instant();
      Instant occurredAt = cmd.occurredAt() != null ? cmd.occurredAt() : now;
      return events.save(new OrderJourneyProjectionEvent(tenantId, eventType, sourceType, cmd.sourceId(),
          bound(cmd.reasonCode(), MAX_REASON), cmd.correlationId(), cmd.causationId(), idempotencyKey, 1,
          bound(cmd.payloadSummary(), MAX_SUMMARY), occurredAt, now));
    });
  }

  /**
   * Convenience idempotent publish for an explicit refresh request. The idempotency key folds the source so
   * repeat requests for the same source in the same window collapse to one durable event.
   */
  @Transactional
  public OrderJourneyProjectionEvent publishRefreshRequest(UUID tenantId, JourneySourceType sourceType,
      UUID sourceId, String reasonCode) {
    return publish(new PublishCommand(tenantId, JourneyProjectionEventType.ORDER_JOURNEY_REFRESH_REQUESTED,
        sourceType, sourceId, reasonCode, null, null, refreshIdempotencyKey(sourceType, sourceId, reasonCode),
        "Projection refresh requested", null));
  }

  /** Stable, bounded idempotency key for an explicit refresh request — repeats collapse to one event. */
  public static String refreshIdempotencyKey(JourneySourceType sourceType, UUID sourceId, String reasonCode) {
    String key = "journey-refresh:" + sourceType + ":" + sourceId
        + (reasonCode != null && !reasonCode.isBlank() ? ":" + reasonCode.strip() : "");
    return key.length() <= MAX_IDEMPOTENCY_KEY ? key : key.substring(0, MAX_IDEMPOTENCY_KEY);
  }

  /** Bounded, tenant-scoped batch of events ready to (re)process. */
  @Transactional(readOnly = true)
  public List<OrderJourneyProjectionEvent> findPendingBatch(UUID tenantId, int limit, Instant now) {
    required(tenantId, "tenantId");
    return events.findPendingBatch(tenantId, MAX_RETRY, now != null ? now : clock.instant(),
        PageRequest.of(0, clampBatch(limit)));
  }

  static int clampBatch(int requested) {
    if (requested <= 0) {
      return DEFAULT_BATCH;
    }
    return Math.min(requested, MAX_BATCH);
  }

  private static String bound(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.strip();
  }

  private static <T> T required(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
