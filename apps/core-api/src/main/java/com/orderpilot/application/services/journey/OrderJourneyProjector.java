package com.orderpilot.application.services.journey;

import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * OP-CAP-23 Event/Outbox-driven Order Journey Projector Runtime — the Order Journey projector.
 *
 * <p>Resolves the trusted internal source row referenced by a durable projection event and idempotently
 * refreshes the {@link OrderJourney} projection through {@link OrderJourneyService}. It NEVER creates or
 * approves orders, quotes, payments, trust decisions, or ERP/PSP/carrier writes, NEVER calls AI, and NEVER
 * exposes a raw payload. Idempotency is owned by the runtime (per-event checkpoint) and by
 * {@code OrderJourneyService.refreshFromSource}, which rebuilds (not appends) the projection's milestones.
 *
 * <p>Outcomes are bounded and deterministic:
 * <ul>
 *   <li>missing/blank {@code sourceId} → throws (runtime records a bounded FAILED with INVALID_PAYLOAD);</li>
 *   <li>non-projectable source type (e.g. EXTERNAL_MIRROR, out of scope this stage) → SKIPPED;</li>
 *   <li>source row not found for (tenant, type, id) → SKIPPED (SOURCE_NOT_FOUND);</li>
 *   <li>source present → PROJECTED (journey id returned).</li>
 * </ul>
 */
@Service
public class OrderJourneyProjector {
  public static final String PROJECTOR_NAME = "OrderJourneyProjector";

  private final OrderJourneyService journeyService;

  public OrderJourneyProjector(OrderJourneyService journeyService) {
    this.journeyService = journeyService;
  }

  /** Outcome of projecting one event (no business-state mutation, ever). */
  public record ProjectionOutcome(Kind kind, String reasonCode, String projectedRecordType,
      UUID projectedRecordId) {
    public enum Kind { PROJECTED, SKIPPED }

    public static ProjectionOutcome projected(String type, UUID id) {
      return new ProjectionOutcome(Kind.PROJECTED, null, type, id);
    }

    public static ProjectionOutcome skipped(String reasonCode) {
      return new ProjectionOutcome(Kind.SKIPPED, reasonCode, null, null);
    }
  }

  /**
   * Projects one event. Runs inside the runtime's per-event transaction. Returns the outcome; throws only on
   * invalid payload or unexpected failure (the runtime turns that into a bounded FAILED/DEAD_LETTERED state).
   */
  public ProjectionOutcome project(OrderJourneyProjectionEvent event) {
    if (event.getSourceId() == null) {
      throw new IllegalArgumentException("sourceId is required to project a journey");
    }
    if (!event.getSourceType().isProjectable()) {
      // No durable internal source to resolve idempotently (external mirror is out of scope this stage).
      return ProjectionOutcome.skipped("UNSUPPORTED_SOURCE");
    }
    Optional<OrderJourney> journey =
        journeyService.refreshIfSourcePresent(event.getSourceType(), event.getSourceId());
    return journey
        .map(j -> ProjectionOutcome.projected("ORDER_JOURNEY", j.getId()))
        .orElseGet(() -> ProjectionOutcome.skipped("SOURCE_NOT_FOUND"));
  }
}
