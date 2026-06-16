package com.orderpilot.application.services.trust;

import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.events.TrustAiDomainEvent;
import com.orderpilot.domain.trust.events.TrustAiDomainEventRepository;
import com.orderpilot.domain.trust.events.TrustAiEventType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-18 Trust/AI Event Projector Runtime — durable internal event publisher.
 *
 * Publishes approved, sanitized, tenant-scoped trust/AI events for the projector runtime to consume.
 * Publishing is idempotent per (tenant, idempotency key): a duplicate publish returns the existing event
 * rather than creating a new one. Payloads are bounded and sanitized — no raw documents/prompts/messages,
 * secrets, or credentials. The pending batch is always bounded and tenant-scoped, and FAILED events are
 * only re-offered while under the retry cap.
 */
@Service
public class TrustAiEventPublisherService {
  /** Max attempts before an event is dead-lettered (no infinite retry). */
  public static final int MAX_RETRY = 3;
  public static final int DEFAULT_BATCH = 50;
  static final int MAX_BATCH = 200;
  static final int MAX_SUMMARY = 512;
  static final int MAX_SUBJECT_TYPE = 32;
  static final int MAX_IDEMPOTENCY_KEY = 160;

  private final TrustAiDomainEventRepository events;
  private Clock clock;

  public TrustAiEventPublisherService(TrustAiDomainEventRepository events, Clock clock) {
    this.events = events;
    this.clock = clock;
  }

  public record PublishEventCommand(
      UUID tenantId, TrustAiEventType eventType, AiMemorySourceType sourceType, UUID sourceId,
      String subjectType, UUID subjectId, String idempotencyKey, String payloadSummary, Instant occurredAt) {}

  /** Idempotent publish: returns the existing event when the (tenant, idempotency key) already exists. */
  @Transactional
  public TrustAiDomainEvent publishEvent(PublishEventCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    TrustAiEventType eventType = required(cmd.eventType(), "eventType");
    AiMemorySourceType sourceType = required(cmd.sourceType(), "sourceType");
    String idempotencyKey = requireText(cmd.idempotencyKey(), "idempotencyKey");
    if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
      throw new IllegalArgumentException("idempotencyKey exceeds " + MAX_IDEMPOTENCY_KEY + " characters");
    }
    return events.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey).orElseGet(() -> {
      Instant now = clock.instant();
      Instant occurredAt = cmd.occurredAt() != null ? cmd.occurredAt() : now;
      return events.save(new TrustAiDomainEvent(tenantId, eventType, sourceType, cmd.sourceId(),
          bound(cmd.subjectType(), MAX_SUBJECT_TYPE), cmd.subjectId(), idempotencyKey, 1,
          bound(cmd.payloadSummary(), MAX_SUMMARY), occurredAt, now));
    });
  }

  /** Convenience idempotent publish for the common case (no subject, occurredAt = now). */
  @Transactional
  public TrustAiDomainEvent publishOnce(UUID tenantId, TrustAiEventType eventType,
      AiMemorySourceType sourceType, UUID sourceId, String idempotencyKey, String payloadSummary) {
    return publishEvent(new PublishEventCommand(
        tenantId, eventType, sourceType, sourceId, null, null, idempotencyKey, payloadSummary, null));
  }

  /** Bounded, tenant-scoped batch of events ready to (re)process. */
  @Transactional(readOnly = true)
  public List<TrustAiDomainEvent> findPendingBatch(UUID tenantId, int limit, Instant now) {
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
