package com.orderpilot.application.services.trust;

import com.orderpilot.api.dto.TrustAiProjectionDtos.ProcessTrustAiEventsResponse;
import com.orderpilot.application.services.trust.AiMemoryEventProjector.ProjectionOutcome;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.trust.events.TrustAiDomainEvent;
import com.orderpilot.domain.trust.events.TrustAiDomainEventRepository;
import com.orderpilot.domain.trust.events.TrustAiEventStatus;
import com.orderpilot.domain.trust.events.TrustAiProjectionCheckpoint;
import com.orderpilot.domain.trust.events.TrustAiProjectionCheckpointRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-18 Trust/AI Event Projector Runtime — controlled, idempotent projector dispatch.
 *
 * Processes durable internal events through {@link AiMemoryEventProjector}, recording a per-(event,
 * projector) {@link TrustAiProjectionCheckpoint} that guarantees an event is never double-projected. There
 * is NO background thread/daemon/scheduler — processing is driven by an explicit, tenant-scoped service
 * call/endpoint. Each event is processed in its own transaction so one failure never rolls back the rest.
 * Failures are bounded: an event retries until the cap then is DEAD_LETTERED (never infinite-looped).
 * Cross-tenant processing is impossible — every lookup is tenant-scoped.
 */
@Service
public class TrustAiProjectorRuntimeService {
  static final long RETRY_BACKOFF_SECONDS = 300; // fixed +5m backoff

  private final TrustAiEventPublisherService publisher;
  private final TrustAiDomainEventRepository events;
  private final TrustAiProjectionCheckpointRepository checkpoints;
  private final AiMemoryEventProjector projector;
  private final TrustAiProjectorRuntimeService self;
  private Clock clock;

  public TrustAiProjectorRuntimeService(
      TrustAiEventPublisherService publisher,
      TrustAiDomainEventRepository events,
      TrustAiProjectionCheckpointRepository checkpoints,
      AiMemoryEventProjector projector,
      @Lazy TrustAiProjectorRuntimeService self,
      Clock clock) {
    this.publisher = publisher;
    this.events = events;
    this.checkpoints = checkpoints;
    this.projector = projector;
    this.self = self;
    this.clock = clock;
  }

  /**
   * Processes a bounded, tenant-scoped batch of pending/retry-ready events, each in its own transaction.
   * Returns a tally of outcomes.
   */
  public ProcessTrustAiEventsResponse processTenantBatch(UUID tenantId, int limit) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    Instant now = clock.instant();
    List<TrustAiDomainEvent> batch = publisher.findPendingBatch(tenantId, limit, now);
    int processed = 0;
    int skipped = 0;
    int failed = 0;
    int deadLettered = 0;
    for (TrustAiDomainEvent event : batch) {
      TrustAiEventStatus status = self.processEvent(tenantId, event.getId()).getStatus();
      switch (status) {
        case PROCESSED -> processed++;
        case SKIPPED -> skipped++;
        case FAILED -> failed++;
        case DEAD_LETTERED -> deadLettered++;
        default -> { /* PENDING/PROCESSING should not occur post-process */ }
      }
    }
    return new ProcessTrustAiEventsResponse(batch.size(), processed, skipped, failed, deadLettered, now);
  }

  /**
   * Processes one event idempotently. A terminal event, or one whose projector checkpoint already
   * succeeded, is a no-op. Runs in its own transaction.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public TrustAiDomainEvent processEvent(UUID tenantId, UUID eventId) {
    TrustAiDomainEvent event = events.findByIdAndTenantId(eventId, tenantId)
        .orElseThrow(() -> new NotFoundException("Trust AI domain event not found"));
    if (event.isTerminal()) {
      return event;
    }
    Instant now = clock.instant();
    TrustAiProjectionCheckpoint checkpoint = checkpoints
        .findByTenantIdAndProjectorNameAndEventId(tenantId, AiMemoryEventProjector.PROJECTOR_NAME, eventId)
        .orElse(null);
    if (checkpoint != null && checkpoint.isTerminalSuccess()) {
      // Already projected/skipped by this projector — keep the event terminal, do not reproject.
      if (!event.isTerminal()) {
        event.markProcessed(now);
        events.save(event);
      }
      return event;
    }
    if (checkpoint == null) {
      checkpoint = new TrustAiProjectionCheckpoint(tenantId, AiMemoryEventProjector.PROJECTOR_NAME, eventId,
          event.getEventType(), event.getSourceType(), event.getSourceId(), event.getIdempotencyKey(), now);
    } else {
      checkpoint.beginAttempt(now);
    }
    event.markProcessing();

    try {
      ProjectionOutcome outcome = projector.project(event);
      switch (outcome.kind()) {
        case PROJECTED, ACKNOWLEDGED -> {
          checkpoint.complete(outcome.projectedRecordType(), outcome.projectedRecordId(), now);
          event.markProcessed(now);
        }
        case SKIPPED -> {
          checkpoint.skip(now);
          event.markSkipped(now);
        }
        default -> { /* exhaustive */ }
      }
    } catch (RuntimeException ex) {
      String code = bound(ex.getClass().getSimpleName(), 48);
      String message = bound(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName(), 512);
      checkpoint.fail(code, message, now);
      if (event.getRetryCount() + 1 >= TrustAiEventPublisherService.MAX_RETRY) {
        event.markDeadLettered(code, message, now);
      } else {
        event.recordFailure(code, message, now.plusSeconds(RETRY_BACKOFF_SECONDS), now);
      }
    }
    checkpoints.save(checkpoint);
    events.save(event);
    return event;
  }

  private static String bound(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
