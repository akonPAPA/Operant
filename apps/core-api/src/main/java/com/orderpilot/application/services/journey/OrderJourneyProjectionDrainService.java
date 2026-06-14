package com.orderpilot.application.services.journey;

import com.orderpilot.api.dto.OrderJourneyProjectionDtos.OrderJourneyProjectionDrainSummary;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.ProcessJourneyProjectionResponse;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEventRepository;
import java.time.Clock;
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
 * OP-CAP-25 Controlled Journey Projector Drain Runtime — a bounded, tenant-safe drain that turns pending
 * {@code OrderJourneyProjectionEvent}s into READY projections WITHOUT a manual {@code projection/process}
 * call, and WITHOUT an unmanaged daemon, infinite loop, or new queue/infrastructure.
 *
 * <p>This service does not re-implement projection: it discovers which tenants currently have drainable work
 * (bounded, oldest-first, via {@code findTenantIdsWithPendingEvents}) and processes each tenant through the
 * existing {@link OrderJourneyProjectorRunner}, which owns the per-event {@code REQUIRES_NEW} transaction,
 * checkpoint/idempotency, retry/backoff and dead-letter behavior from OP-CAP-23. Therefore:
 * <ul>
 *   <li>every limit is clamped — there is never an unbounded tenant scan or unbounded per-tenant batch;</li>
 *   <li>a failure for one tenant is caught and does not stop the remaining tenants (fairness/liveness);</li>
 *   <li>DEAD_LETTERED events are excluded by discovery and never retried;</li>
 *   <li>old events are processed first so no tenant or event is starved;</li>
 *   <li>the returned summary carries only counts/flags — no tenant names, customer data, or raw payload.</li>
 * </ul>
 *
 * <p>It is driven either by the bounded admin/runtime endpoint (current-tenant scope) or by the
 * config-gated {@code OrderJourneyProjectionScheduledDrain} (system, multi-tenant) — never by the frontend,
 * AI, or any external caller.
 */
@Service
public class OrderJourneyProjectionDrainService {
  private static final Logger log = LoggerFactory.getLogger(OrderJourneyProjectionDrainService.class);

  /** Hard ceiling on tenants visited per drain cycle, independent of (possibly mis-)configured limits. */
  static final int MAX_TENANTS_PER_CYCLE = 50;
  static final int DEFAULT_TENANTS_PER_CYCLE = 10;

  private final OrderJourneyProjectionEventRepository events;
  private final OrderJourneyProjectorRunner runner;
  private final Clock clock;
  private final int configuredTenantsPerCycle;
  private final int configuredBatchSize;

  public OrderJourneyProjectionDrainService(
      OrderJourneyProjectionEventRepository events,
      OrderJourneyProjectorRunner runner,
      Clock clock,
      @Value("${orderpilot.runtime.order-journey-projection.max-tenants-per-cycle:10}")
          int configuredTenantsPerCycle,
      @Value("${orderpilot.runtime.order-journey-projection.batch-size:25}") int configuredBatchSize) {
    this.events = events;
    this.runner = runner;
    this.clock = clock;
    this.configuredTenantsPerCycle = clampTenants(configuredTenantsPerCycle);
    this.configuredBatchSize = OrderJourneyProjectionPublisher.clampBatch(configuredBatchSize);
  }

  /** Drains using the configured (clamped) tenant and per-tenant limits. Used by the scheduled drain. */
  public OrderJourneyProjectionDrainSummary drainOnce() {
    return drainTenants(configuredTenantsPerCycle, configuredBatchSize);
  }

  /**
   * Drains a single tenant's bounded batch through the existing runner. Tenant-scoped end to end — safe to
   * expose to an authenticated, permission-checked, tenant-resolved request.
   */
  public OrderJourneyProjectionDrainSummary drainTenant(UUID tenantId, int perTenantLimit) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    int limit = OrderJourneyProjectionPublisher.clampBatch(perTenantLimit);
    ProcessJourneyProjectionResponse res = processWithTenantContext(tenantId, limit);
    return new OrderJourneyProjectionDrainSummary(1, res.processed(), res.skipped(), res.failed(),
        res.deadLettered(), false, limit, clock.instant());
  }

  /**
   * Discovers up to {@code tenantLimit} tenants with drainable work (oldest-first, bounded, no payload) and
   * drains each through the runner. A per-tenant failure is caught and logged WITHOUT the tenant id, so one
   * unhealthy tenant never blocks the rest of the cycle. {@code partial} is set when the tenant scan filled
   * its clamp (more tenants may still have pending work for the next cycle).
   */
  public OrderJourneyProjectionDrainSummary drainTenants(int tenantLimit, int perTenantLimit) {
    int tenants = clampTenants(tenantLimit);
    int limit = OrderJourneyProjectionPublisher.clampBatch(perTenantLimit);
    Instant now = clock.instant();
    List<UUID> tenantIds = events.findTenantIdsWithPendingEvents(
        OrderJourneyProjectionPublisher.MAX_RETRY, now, PageRequest.of(0, tenants));

    int scanned = 0;
    int processed = 0;
    int skipped = 0;
    int failed = 0;
    int deadLettered = 0;
    for (UUID tenantId : tenantIds) {
      scanned++;
      try {
        ProcessJourneyProjectionResponse res = processWithTenantContext(tenantId, limit);
        processed += res.processed();
        skipped += res.skipped();
        failed += res.failed();
        deadLettered += res.deadLettered();
      } catch (RuntimeException ex) {
        // Bounded, tenant-anonymous: never log the tenant id or any payload — only the error class.
        log.warn("Order journey projection drain skipped a tenant after error: {}",
            ex.getClass().getSimpleName());
      }
    }
    boolean partial = tenantIds.size() >= tenants;
    return new OrderJourneyProjectionDrainSummary(scanned, processed, skipped, failed, deadLettered,
        partial, limit, clock.instant());
  }

  /** Cheap, bounded count of tenants with drainable work right now (clamped scan, tenant ids only). */
  @Transactional(readOnly = true)
  public int pendingTenantCount() {
    return events.findTenantIdsWithPendingEvents(OrderJourneyProjectionPublisher.MAX_RETRY, clock.instant(),
        PageRequest.of(0, MAX_TENANTS_PER_CYCLE)).size();
  }

  /**
   * Processes one tenant's bounded batch with that tenant's {@link TenantContext} bound for the duration —
   * the projector resolves the trusted source row via the ambient tenant, so the system/scheduled drain
   * (which has no inbound request tenant) MUST set it. The previous tenant context (if any, e.g. an operator
   * request) is restored in {@code finally}, so this never leaks one tenant's context into another's work.
   */
  private ProcessJourneyProjectionResponse processWithTenantContext(UUID tenantId, int limit) {
    Optional<UUID> previous = TenantContext.getTenantId();
    try {
      TenantContext.setTenantId(tenantId);
      return runner.processTenantBatch(tenantId, limit);
    } finally {
      previous.ifPresentOrElse(TenantContext::setTenantId, TenantContext::clear);
    }
  }

  private static int clampTenants(int requested) {
    if (requested <= 0) {
      return DEFAULT_TENANTS_PER_CYCLE;
    }
    return Math.min(requested, MAX_TENANTS_PER_CYCLE);
  }
}
