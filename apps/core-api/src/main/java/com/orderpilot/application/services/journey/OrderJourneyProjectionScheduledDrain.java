package com.orderpilot.application.services.journey;

import com.orderpilot.api.dto.OrderJourneyProjectionDtos.OrderJourneyProjectionDrainSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OP-CAP-25 — the controlled, config-gated scheduled trigger for the Order Journey projection drain.
 *
 * <p>This bean only exists when {@code orderpilot.runtime.order-journey-projection.enabled=true} (default
 * false, including every test profile), so there is no always-on background processing. When enabled, a
 * single Spring-managed scheduled task runs at a fixed delay and delegates to
 * {@link OrderJourneyProjectionDrainService#drainOnce()} — a bounded, tenant-fair, idempotent drain. It is
 * NOT an unmanaged thread and NOT an infinite loop: each tick does a clamped amount of work and returns.
 *
 * <p>Any error is caught so a transient failure never kills the scheduler, and nothing tenant-sensitive
 * (tenant ids, customer data, payloads) is logged — only bounded counts and error classes.
 */
@Component
@ConditionalOnProperty(name = "orderpilot.runtime.order-journey-projection.enabled", havingValue = "true")
public class OrderJourneyProjectionScheduledDrain {
  private static final Logger log = LoggerFactory.getLogger(OrderJourneyProjectionScheduledDrain.class);

  private final OrderJourneyProjectionDrainService drainService;

  public OrderJourneyProjectionScheduledDrain(OrderJourneyProjectionDrainService drainService) {
    this.drainService = drainService;
  }

  @Scheduled(
      fixedDelayString = "${orderpilot.runtime.order-journey-projection.fixed-delay-ms:30000}",
      initialDelayString = "${orderpilot.runtime.order-journey-projection.fixed-delay-ms:30000}")
  public void drainScheduled() {
    try {
      OrderJourneyProjectionDrainSummary summary = drainService.drainOnce();
      if (summary.tenantsScanned() > 0) {
        log.info("Order journey projection drain: tenants={} processed={} skipped={} failed={} "
                + "deadLettered={} partial={}", summary.tenantsScanned(), summary.eventsProcessed(),
            summary.eventsSkipped(), summary.eventsFailed(), summary.eventsDeadLettered(), summary.partial());
      }
    } catch (RuntimeException ex) {
      // Keep the scheduler alive; never leak tenant-sensitive data.
      log.warn("Order journey projection scheduled drain cycle failed: {}", ex.getClass().getSimpleName());
    }
  }
}
