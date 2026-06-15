package com.orderpilot.application.services.journey;

import com.orderpilot.api.dto.OrderJourneyProjectionDtos.JourneyProjectionFailureDto;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.JourneyProjectionHealthDto;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.OrderJourneyProjectionDrainSummary;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.OrderJourneyProjectionRecoverySummary;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.events.JourneyProjectionCheckpointStatus;
import com.orderpilot.domain.journey.events.JourneyProjectionEventStatus;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionCheckpoint;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionCheckpointRepository;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEvent;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-25/26 controlled Journey Projector drain runtime: bounded tenant discovery, missed-event recovery,
 * and production-safe drain health. Projection remains delegated to {@link OrderJourneyProjectorRunner}.
 */
@Service
public class OrderJourneyProjectionDrainService {
  private static final Logger log = LoggerFactory.getLogger(OrderJourneyProjectionDrainService.class);
  private static final int FAILURE_PREVIEW_LIMIT = 20;

  /** Hard ceiling on tenants visited per drain cycle, independent of (possibly mis-)configured limits. */
  static final int MAX_TENANTS_PER_CYCLE = 50;
  static final int DEFAULT_TENANTS_PER_CYCLE = 10;
  static final long DEFAULT_STALE_PROCESSING_SECONDS = 900;

  private final OrderJourneyProjectionEventRepository events;
  private final OrderJourneyProjectionCheckpointRepository checkpoints;
  private final OrderJourneyProjectorRunner runner;
  private final Clock clock;
  private final int configuredTenantsPerCycle;
  private final int configuredBatchSize;
  private final boolean schedulerEnabled;
  private final long staleProcessingSeconds;

  private volatile Instant lastDrainStartedAt;
  private volatile Instant lastDrainCompletedAt;
  private volatile Long lastDrainDurationMs;
  private volatile String lastDrainStatus;
  private volatile String lastDrainErrorCode;
  private volatile String lastDrainErrorMessageSafe;
  private volatile Instant lastRecoveredAt;
  private volatile int lastRecoveryRecoveredCount;

  public OrderJourneyProjectionDrainService(
      OrderJourneyProjectionEventRepository events,
      OrderJourneyProjectionCheckpointRepository checkpoints,
      OrderJourneyProjectorRunner runner,
      Clock clock,
      @Value("${orderpilot.runtime.order-journey-projection.max-tenants-per-cycle:10}")
          int configuredTenantsPerCycle,
      @Value("${orderpilot.runtime.order-journey-projection.batch-size:25}") int configuredBatchSize,
      @Value("${orderpilot.runtime.order-journey-projection.enabled:false}") boolean schedulerEnabled,
      @Value("${orderpilot.runtime.order-journey-projection.recovery-stale-processing-seconds:900}")
          long staleProcessingSeconds) {
    this.events = events;
    this.checkpoints = checkpoints;
    this.runner = runner;
    this.clock = clock;
    this.configuredTenantsPerCycle = clampTenants(configuredTenantsPerCycle);
    this.configuredBatchSize = OrderJourneyProjectionPublisher.clampBatch(configuredBatchSize);
    this.schedulerEnabled = schedulerEnabled;
    this.staleProcessingSeconds = staleProcessingSeconds > 0
        ? staleProcessingSeconds : DEFAULT_STALE_PROCESSING_SECONDS;
  }

  /** Drains using the configured (clamped) tenant and per-tenant limits. Used by the scheduled drain. */
  public OrderJourneyProjectionDrainSummary drainOnce() {
    return trackDrain(() -> drainTenantsInternal(configuredTenantsPerCycle, configuredBatchSize));
  }

  /**
   * Drains a single tenant's bounded batch through the recovery-aware path. Tenant-scoped end to end.
   */
  public OrderJourneyProjectionDrainSummary drainTenant(UUID tenantId, int perTenantLimit) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    return trackDrain(() -> {
      OrderJourneyProjectionRecoverySummary res =
          recoverTenantInternal(tenantId, perTenantLimit, clock.instant());
      rememberRecovery(res.recoveredCount());
      return new OrderJourneyProjectionDrainSummary(1, res.recoveredCount(), res.skippedCount(),
          res.failedCount(), res.deadLetteredCount(), false, res.limitApplied(), res.generatedAt());
    });
  }

  /**
   * Discovers tenants with recoverable work and drains each through the runner. A per-tenant failure is
   * caught without tenant id/payload logging, so one unhealthy tenant cannot block the cycle.
   */
  public OrderJourneyProjectionDrainSummary drainTenants(int tenantLimit, int perTenantLimit) {
    return trackDrain(() -> drainTenantsInternal(tenantLimit, perTenantLimit));
  }

  /** OP-CAP-26 tenant-scoped missed-event recovery entry point. */
  public OrderJourneyProjectionRecoverySummary recoverMissedEvents(UUID tenantId, int batchSize, Instant now) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    OrderJourneyProjectionRecoverySummary summary =
        recoverTenantInternal(tenantId, batchSize, now != null ? now : clock.instant());
    rememberRecovery(summary.recoveredCount());
    return summary;
  }

  /** Production-safe, tenant-scoped drain/projector health read model. */
  @Transactional(readOnly = true)
  public JourneyProjectionHealthDto health(UUID tenantId) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    Instant now = clock.instant();
    Instant staleBefore = staleBefore(now);
    long pending = events.countByTenantIdAndStatus(tenantId, JourneyProjectionEventStatus.PENDING);
    long inProgress = events.countByTenantIdAndStatus(tenantId, JourneyProjectionEventStatus.PROCESSING);
    long staleInProgress =
        events.countStaleProcessingEvents(tenantId, OrderJourneyProjector.PROJECTOR_NAME, staleBefore);
    long failedEvents = events.countByTenantIdAndStatus(tenantId, JourneyProjectionEventStatus.FAILED);
    long failedRetryable =
        events.countRetryableFailedEvents(tenantId, OrderJourneyProjectionPublisher.MAX_RETRY);
    long failedPermanent =
        events.countPermanentFailedEvents(tenantId, OrderJourneyProjectionPublisher.MAX_RETRY);
    long deadLettered = events.countByTenantIdAndStatus(tenantId, JourneyProjectionEventStatus.DEAD_LETTERED);
    long failedCheckpoints =
        checkpoints.countByTenantIdAndStatus(tenantId, JourneyProjectionCheckpointStatus.FAILED);
    List<OrderJourneyProjectionEvent> recentProcessed = events.findByTenantIdAndStatusOrderByOccurredAtDesc(
        tenantId, JourneyProjectionEventStatus.PROCESSED, PageRequest.of(0, 1));
    Instant lastProcessedAt = recentProcessed.isEmpty() ? null : recentProcessed.get(0).getProcessedAt();
    UUID lastProcessedEventId = recentProcessed.isEmpty() ? null : recentProcessed.get(0).getId();
    List<OrderJourneyProjectionEvent> oldestPending =
        events.findByTenantIdAndStatusOrderByOccurredAtAsc(
            tenantId, JourneyProjectionEventStatus.PENDING, PageRequest.of(0, 1));
    Instant oldestPendingAt = oldestPending.isEmpty() ? null : oldestPending.get(0).getOccurredAt();
    Long oldestPendingAgeSeconds = oldestPendingAt == null ? null
        : Math.max(0L, Duration.between(oldestPendingAt, now).getSeconds());
    List<JourneyProjectionFailureDto> recentFailures = checkpoints
        .findByTenantIdAndStatusOrderByUpdatedAtDesc(tenantId, JourneyProjectionCheckpointStatus.FAILED,
            PageRequest.of(0, FAILURE_PREVIEW_LIMIT))
        .stream()
        .map(c -> new JourneyProjectionFailureDto(c.getEventId(), c.getEventType().name(),
            c.getSourceType().name(), c.getSourceId(), c.getStatus().name(), c.getFailureCode(),
            c.getFailureMessage(), c.getAttemptCount(), c.getFailedAt()))
        .toList();
    OrderJourneyProjectionCheckpoint lastCheckpoint = checkpoints
        .findFirstByTenantIdAndProjectorNameOrderByUpdatedAtDesc(
            tenantId, OrderJourneyProjector.PROJECTOR_NAME)
        .orElse(null);
    return new JourneyProjectionHealthDto(pending, inProgress, staleInProgress, failedEvents,
        failedRetryable, failedPermanent, deadLettered, failedCheckpoints, lastProcessedAt,
        lastProcessedEventId, recentFailures, oldestPendingAt, oldestPendingAgeSeconds,
        schedulerEnabled, configuredBatchSize, lastDrainStartedAt, lastDrainCompletedAt, lastDrainDurationMs,
        lastDrainStatus, lastDrainErrorCode, lastDrainErrorMessageSafe,
        lastCheckpoint == null ? null : lastCheckpoint.getEventId(),
        lastCheckpoint == null ? null : lastCheckpoint.getStatus().name(),
        lastCheckpoint == null ? null : lastCheckpoint.getUpdatedAt(), lastRecoveredAt,
        lastRecoveryRecoveredCount, now);
  }

  /** Cheap, bounded count of tenants with recoverable work right now (clamped scan, tenant ids only). */
  @Transactional(readOnly = true)
  public int pendingTenantCount() {
    Instant now = clock.instant();
    return events.findTenantIdsWithRecoverableEvents(OrderJourneyProjectionPublisher.MAX_RETRY, now,
        OrderJourneyProjector.PROJECTOR_NAME, staleBefore(now), PageRequest.of(0, MAX_TENANTS_PER_CYCLE))
        .size();
  }

  private OrderJourneyProjectionDrainSummary drainTenantsInternal(int tenantLimit, int perTenantLimit) {
    int tenants = clampTenants(tenantLimit);
    int limit = OrderJourneyProjectionPublisher.clampBatch(perTenantLimit);
    Instant now = clock.instant();
    List<UUID> tenantIds = events.findTenantIdsWithRecoverableEvents(
        OrderJourneyProjectionPublisher.MAX_RETRY, now, OrderJourneyProjector.PROJECTOR_NAME,
        staleBefore(now), PageRequest.of(0, tenants));

    int scanned = 0;
    int processed = 0;
    int skipped = 0;
    int failed = 0;
    int deadLettered = 0;
    int recovered = 0;
    for (UUID tenantId : tenantIds) {
      scanned++;
      try {
        OrderJourneyProjectionRecoverySummary res = recoverTenantInternal(tenantId, limit, now);
        processed += res.recoveredCount();
        skipped += res.skippedCount();
        failed += res.failedCount();
        deadLettered += res.deadLetteredCount();
        recovered += res.recoveredCount();
      } catch (RuntimeException ex) {
        log.warn("Order journey projection drain skipped a tenant after error: {}",
            ex.getClass().getSimpleName());
      }
    }
    rememberRecovery(recovered);
    boolean partial = tenantIds.size() >= tenants;
    return new OrderJourneyProjectionDrainSummary(scanned, processed, skipped, failed, deadLettered,
        partial, limit, clock.instant());
  }

  private OrderJourneyProjectionRecoverySummary recoverTenantInternal(UUID tenantId, int batchSize, Instant now) {
    int limit = OrderJourneyProjectionPublisher.clampBatch(batchSize);
    List<OrderJourneyProjectionEvent> batch = events.findRecoverableBatch(tenantId,
        OrderJourneyProjectionPublisher.MAX_RETRY, now, OrderJourneyProjector.PROJECTOR_NAME,
        staleBefore(now), PageRequest.of(0, limit));
    List<OrderJourneyProjectionEvent> oldestPending =
        events.findByTenantIdAndStatusOrderByOccurredAtAsc(
            tenantId, JourneyProjectionEventStatus.PENDING, PageRequest.of(0, 1));
    Long oldestPendingAgeSeconds = oldestPending.isEmpty() ? null
        : Math.max(0L, Duration.between(oldestPending.get(0).getOccurredAt(), now).getSeconds());
    int recovered = 0;
    int skipped = 0;
    int failed = 0;
    int deadLettered = 0;
    int staleInProgress = 0;
    int retryScheduled = 0;
    UUID lastRecovered = null;

    for (OrderJourneyProjectionEvent event : batch) {
      JourneyProjectionEventStatus before = event.getStatus();
      if (before == JourneyProjectionEventStatus.PROCESSING) {
        staleInProgress++;
        event.requeueStaleProcessingForRecovery(now);
        events.save(event);
      } else if (before == JourneyProjectionEventStatus.FAILED) {
        retryScheduled++;
      }
      JourneyProjectionEventStatus after = processEventWithTenantContext(tenantId, event.getId()).getStatus();
      switch (after) {
        case PROCESSED -> {
          recovered++;
          lastRecovered = event.getId();
        }
        case SKIPPED -> skipped++;
        case FAILED -> failed++;
        case DEAD_LETTERED -> deadLettered++;
        default -> { /* A concurrent worker may leave non-terminal state; the next bounded pass can recover. */ }
      }
    }
    return new OrderJourneyProjectionRecoverySummary(tenantId, batch.size(), recovered, skipped, failed,
        deadLettered, staleInProgress, retryScheduled, oldestPendingAgeSeconds, lastRecovered, limit,
        clock.instant());
  }

  private OrderJourneyProjectionEvent processEventWithTenantContext(UUID tenantId, UUID eventId) {
    Optional<UUID> previous = TenantContext.getTenantId();
    try {
      TenantContext.setTenantId(tenantId);
      return runner.processEvent(tenantId, eventId);
    } finally {
      previous.ifPresentOrElse(TenantContext::setTenantId, TenantContext::clear);
    }
  }

  private OrderJourneyProjectionDrainSummary trackDrain(DrainOperation operation) {
    Instant started = clock.instant();
    lastDrainStartedAt = started;
    lastDrainStatus = "RUNNING";
    lastDrainErrorCode = null;
    lastDrainErrorMessageSafe = null;
    try {
      OrderJourneyProjectionDrainSummary summary = operation.run();
      Instant completed = clock.instant();
      lastDrainCompletedAt = completed;
      lastDrainDurationMs = Duration.between(started, completed).toMillis();
      lastDrainStatus = "SUCCEEDED";
      return summary;
    } catch (RuntimeException ex) {
      Instant completed = clock.instant();
      lastDrainCompletedAt = completed;
      lastDrainDurationMs = Duration.between(started, completed).toMillis();
      lastDrainStatus = "FAILED";
      lastDrainErrorCode = ex.getClass().getSimpleName();
      lastDrainErrorMessageSafe = ex.getClass().getSimpleName();
      throw ex;
    }
  }

  private void rememberRecovery(int recoveredCount) {
    lastRecoveredAt = clock.instant();
    lastRecoveryRecoveredCount = recoveredCount;
  }

  private Instant staleBefore(Instant now) {
    return now.minusSeconds(staleProcessingSeconds);
  }

  private static int clampTenants(int requested) {
    if (requested <= 0) {
      return DEFAULT_TENANTS_PER_CYCLE;
    }
    return Math.min(requested, MAX_TENANTS_PER_CYCLE);
  }

  @FunctionalInterface
  private interface DrainOperation {
    OrderJourneyProjectionDrainSummary run();
  }
}
