package com.orderpilot.application.services.journey;

import com.orderpilot.api.dto.OrderJourneyProjectionDtos.JourneyProjectionFailureDto;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.JourneyProjectionHealthDto;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.JourneyProjectionRequestResponse;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.ProcessJourneyProjectionResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.journey.OrderJourneyProjector.ProjectionOutcome;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.events.JourneyProjectionCheckpointStatus;
import com.orderpilot.domain.journey.events.JourneyProjectionEventStatus;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionCheckpoint;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionCheckpointRepository;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEvent;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-23 Event/Outbox-driven Order Journey Projector Runtime — controlled, idempotent projector dispatch.
 *
 * <p>Processes durable {@link OrderJourneyProjectionEvent}s through {@link OrderJourneyProjector}, recording
 * a per-(event, projector) {@link OrderJourneyProjectionCheckpoint} that guarantees an event is never
 * double-projected. There is NO background thread/daemon/scheduler — processing is driven by an explicit,
 * tenant-scoped service call/endpoint. Each event is processed in its own transaction so one failure never
 * rolls back the rest. Failures are bounded: an event retries (with backoff) until the cap then is
 * DEAD_LETTERED (never infinite-looped). Cross-tenant processing is impossible — every lookup is
 * tenant-scoped, and event ids are never trusted across tenants.
 */
@Service
public class OrderJourneyProjectorRunner {
  static final long RETRY_BACKOFF_SECONDS = 300; // fixed +5m backoff
  private static final int FAILURE_PREVIEW_LIMIT = 20;

  private final OrderJourneyProjectionPublisher publisher;
  private final OrderJourneyProjectionEventRepository events;
  private final OrderJourneyProjectionCheckpointRepository checkpoints;
  private final OrderJourneyProjector projector;
  private final AuditEventService auditEventService;
  private final OrderJourneyProjectorRunner self;
  private final Clock clock;
  private final boolean schedulerEnabled;
  private final int configuredBatchSize;

  public OrderJourneyProjectorRunner(
      OrderJourneyProjectionPublisher publisher,
      OrderJourneyProjectionEventRepository events,
      OrderJourneyProjectionCheckpointRepository checkpoints,
      OrderJourneyProjector projector,
      AuditEventService auditEventService,
      @Lazy OrderJourneyProjectorRunner self,
      Clock clock,
      @Value("${orderpilot.runtime.order-journey-projection.enabled:false}") boolean schedulerEnabled,
      @Value("${orderpilot.runtime.order-journey-projection.batch-size:25}") int configuredBatchSize) {
    this.publisher = publisher;
    this.events = events;
    this.checkpoints = checkpoints;
    this.projector = projector;
    this.auditEventService = auditEventService;
    this.self = self;
    this.clock = clock;
    this.schedulerEnabled = schedulerEnabled;
    this.configuredBatchSize = OrderJourneyProjectionPublisher.clampBatch(configuredBatchSize);
  }

  /**
   * Processes a bounded, tenant-scoped batch of pending/retry-ready events, each in its own transaction.
   * Returns a tally of outcomes.
   */
  public ProcessJourneyProjectionResponse processTenantBatch(UUID tenantId, int limit) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    Instant now = clock.instant();
    List<OrderJourneyProjectionEvent> batch = publisher.findPendingBatch(tenantId, limit, now);
    int processed = 0;
    int skipped = 0;
    int failed = 0;
    int deadLettered = 0;
    for (OrderJourneyProjectionEvent event : batch) {
      JourneyProjectionEventStatus status = self.processEvent(tenantId, event.getId()).getStatus();
      switch (status) {
        case PROCESSED -> processed++;
        case SKIPPED -> skipped++;
        case FAILED -> failed++;
        case DEAD_LETTERED -> deadLettered++;
        default -> { /* PENDING/PROCESSING should not occur post-process */ }
      }
    }
    return new ProcessJourneyProjectionResponse(batch.size(), processed, skipped, failed, deadLettered, now);
  }

  /**
   * Processes one event idempotently. A terminal event, or one whose projector checkpoint already succeeded,
   * is a no-op. Runs in its own transaction.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public OrderJourneyProjectionEvent processEvent(UUID tenantId, UUID eventId) {
    OrderJourneyProjectionEvent event = events.findByIdAndTenantId(eventId, tenantId)
        .orElseThrow(() -> new NotFoundException("Order journey projection event not found"));
    if (event.isTerminal()) {
      return event;
    }
    Instant now = clock.instant();
    OrderJourneyProjectionCheckpoint checkpoint = checkpoints
        .findByTenantIdAndProjectorNameAndEventId(tenantId, OrderJourneyProjector.PROJECTOR_NAME, eventId)
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
      checkpoint = new OrderJourneyProjectionCheckpoint(tenantId, OrderJourneyProjector.PROJECTOR_NAME, eventId,
          event.getEventType(), event.getSourceType(), event.getSourceId(), event.getIdempotencyKey(), now);
    } else {
      checkpoint.beginAttempt(now);
    }
    event.markProcessing();

    try {
      ProjectionOutcome outcome = projector.project(event);
      switch (outcome.kind()) {
        case PROJECTED -> {
          checkpoint.complete(outcome.projectedRecordType(), outcome.projectedRecordId(), now);
          event.markProcessed(now);
        }
        case SKIPPED -> {
          checkpoint.skip(outcome.reasonCode(), now);
          event.markSkipped(now);
        }
        default -> { /* exhaustive */ }
      }
    } catch (RuntimeException ex) {
      String code = bound(ex.getClass() == IllegalArgumentException.class ? "INVALID_PAYLOAD"
          : ex.getClass().getSimpleName(), 48);
      String message = bound(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName(), 512);
      checkpoint.fail(code, message, now);
      if (event.getRetryCount() + 1 >= OrderJourneyProjectionPublisher.MAX_RETRY) {
        event.markDeadLettered(code, message, now);
      } else {
        event.recordFailure(code, message, now.plusSeconds(RETRY_BACKOFF_SECONDS), now);
      }
    }
    checkpoints.save(checkpoint);
    events.save(event);
    return event;
  }

  /**
   * Publishes an explicit, idempotent projection request for a known source and audits it. No external write.
   * Returns whether the durable event already existed (duplicate request).
   */
  @Transactional
  public JourneyProjectionRequestResponse requestProjection(UUID tenantId, JourneySourceType sourceType,
      UUID sourceId, String reasonCode, UUID actorId) {
    if (tenantId == null || sourceType == null || sourceId == null) {
      throw new IllegalArgumentException("tenantId, sourceType and sourceId are required");
    }
    boolean existed = events.findByTenantIdAndIdempotencyKey(tenantId,
        OrderJourneyProjectionPublisher.refreshIdempotencyKey(sourceType, sourceId, reasonCode)).isPresent();
    OrderJourneyProjectionEvent event = publisher.publishRefreshRequest(tenantId, sourceType, sourceId, reasonCode);
    auditEventService.record("ORDER_JOURNEY_PROJECTION_REQUESTED", "ORDER_JOURNEY_PROJECTION_EVENT",
        event.getId().toString(), actorId,
        "{\"sourceType\":\"" + sourceType + "\",\"alreadyExisted\":" + existed + "}");
    return new JourneyProjectionRequestResponse(event.getId(), event.getEventType().name(),
        event.getSourceType().name(), event.getSourceId(), event.getStatus().name(), existed, clock.instant());
  }

  /** Bounded, tenant-scoped projector health snapshot. */
  @Transactional(readOnly = true)
  public JourneyProjectionHealthDto health(UUID tenantId) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    long pending = events.countByTenantIdAndStatus(tenantId, JourneyProjectionEventStatus.PENDING);
    long failedEvents = events.countByTenantIdAndStatus(tenantId, JourneyProjectionEventStatus.FAILED);
    long deadLettered = events.countByTenantIdAndStatus(tenantId, JourneyProjectionEventStatus.DEAD_LETTERED);
    long failedCheckpoints =
        checkpoints.countByTenantIdAndStatus(tenantId, JourneyProjectionCheckpointStatus.FAILED);
    List<OrderJourneyProjectionEvent> recentProcessed = events.findByTenantIdAndStatusOrderByOccurredAtDesc(
        tenantId, JourneyProjectionEventStatus.PROCESSED, PageRequest.of(0, 1));
    Instant lastProcessedAt = recentProcessed.isEmpty() ? null : recentProcessed.get(0).getProcessedAt();
    // Oldest drainable (PENDING / retry-ready FAILED) event for staleness monitoring — bounded to one row.
    List<OrderJourneyProjectionEvent> oldestPending =
        publisher.findPendingBatch(tenantId, 1, clock.instant());
    Instant oldestPendingAt = oldestPending.isEmpty() ? null : oldestPending.get(0).getOccurredAt();
    List<JourneyProjectionFailureDto> recentFailures = checkpoints
        .findByTenantIdAndStatusOrderByUpdatedAtDesc(tenantId, JourneyProjectionCheckpointStatus.FAILED,
            PageRequest.of(0, FAILURE_PREVIEW_LIMIT))
        .stream()
        .map(c -> new JourneyProjectionFailureDto(c.getEventId(), c.getEventType().name(),
            c.getSourceType().name(), c.getSourceId(), c.getStatus().name(), c.getFailureCode(),
            c.getFailureMessage(), c.getAttemptCount(), c.getFailedAt()))
        .toList();
    return new JourneyProjectionHealthDto(pending, failedEvents, deadLettered, failedCheckpoints,
        lastProcessedAt, recentFailures, oldestPendingAt, schedulerEnabled, configuredBatchSize,
        clock.instant());
  }

  private static String bound(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
