package com.orderpilot.application.services.journey;

import com.orderpilot.api.dto.OrderJourneyDtos.CustomerSafeJourneyDto;
import com.orderpilot.api.dto.OrderJourneyDtos.CustomerTrackingEventDto;
import com.orderpilot.api.dto.OrderJourneyDtos.CustomerTrackingMilestoneDto;
import com.orderpilot.api.dto.OrderJourneyDtos.FulfillmentSignalDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyAttentionSummaryDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyDetailDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyEventDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyListItemDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyMilestoneDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OperatorFulfillmentTimelineResponse;
import com.orderpilot.api.dto.OrderJourneyDtos.OperatorTimelineEntry;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneySummaryDto;
import com.orderpilot.api.dto.OrderJourneyDtos.PublicOrderTrackingView;
import com.orderpilot.api.dto.OrderJourneyDtos.PublicTrackingMilestoneDto;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.EvidenceLevel;
import com.orderpilot.domain.journey.FulfillmentSignal;
import com.orderpilot.domain.journey.FulfillmentSignalRepository;
import com.orderpilot.domain.journey.FulfillmentSignalType;
import com.orderpilot.domain.journey.MilestoneState;
import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.domain.journey.OrderJourneyEvent;
import com.orderpilot.domain.journey.OrderJourneyEventRepository;
import com.orderpilot.domain.journey.OrderJourneyMilestone;
import com.orderpilot.domain.journey.OrderJourneyMilestoneRepository;
import com.orderpilot.domain.journey.OrderJourneyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-22 — read-only, tenant-scoped, bounded views over the order journey projection. Every list
 * is capped; detail loads milestones (sorted), recent events, and recent signals with explicit
 * limits. No source recomputation happens here (that is {@link OrderJourneyService}).
 */
@Service
public class OrderJourneyReadService {
  private static final int LIST_LIMIT = 50;
  private static final int ATTENTION_LIMIT = 50;
  private static final int EVENT_LIMIT = 20;
  private static final int SIGNAL_LIMIT = 20;
  private static final int TIMELINE_SIGNAL_LIMIT = 100;
  // Deterministic timeline ordering: receivedAt ascending, then stable tiebreakers (signal type name,
  // then the signal id) so two signals sharing a receivedAt always render in the same order.
  private static final Comparator<FulfillmentSignal> TIMELINE_ORDER = Comparator
      .comparing(FulfillmentSignal::getReceivedAt)
      .thenComparing(s -> s.getSignalType().name())
      .thenComparing(FulfillmentSignal::getId);
  private static final List<String> ATTENTION_RISK_LEVELS = List.of("HIGH");

  private final OrderJourneyRepository journeyRepository;
  private final OrderJourneyMilestoneRepository milestoneRepository;
  private final OrderJourneyEventRepository eventRepository;
  private final FulfillmentSignalRepository signalRepository;
  private final Clock clock;

  public OrderJourneyReadService(OrderJourneyRepository journeyRepository,
      OrderJourneyMilestoneRepository milestoneRepository, OrderJourneyEventRepository eventRepository,
      FulfillmentSignalRepository signalRepository, Clock clock) {
    this.journeyRepository = journeyRepository;
    this.milestoneRepository = milestoneRepository;
    this.eventRepository = eventRepository;
    this.signalRepository = signalRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public OrderJourneySummaryDto list(int limit) {
    UUID tenantId = TenantContext.requireTenantId();
    int capped = clamp(limit, LIST_LIMIT);
    List<OrderJourneyListItemDto> items = journeyRepository
        .findByTenantIdOrderByUpdatedAtDesc(tenantId, PageRequest.of(0, capped)).stream()
        .map(this::toListItem)
        .toList();
    long total = journeyRepository.countByTenantId(tenantId);
    long blocked = journeyRepository.countByTenantIdAndBlockedTrue(tenantId);
    return new OrderJourneySummaryDto(items, total, blocked, capped, total > items.size(), clock.instant());
  }

  @Transactional(readOnly = true)
  public OrderJourneyAttentionSummaryDto attention(int limit) {
    UUID tenantId = TenantContext.requireTenantId();
    int capped = clamp(limit, ATTENTION_LIMIT);
    List<OrderJourneyListItemDto> items = journeyRepository
        .findAttention(tenantId, ATTENTION_RISK_LEVELS, PageRequest.of(0, capped)).stream()
        .map(this::toListItem)
        .toList();
    long attentionTotal = journeyRepository.countAttention(tenantId, ATTENTION_RISK_LEVELS);
    long blocked = journeyRepository.countByTenantIdAndBlockedTrue(tenantId);
    return new OrderJourneyAttentionSummaryDto(items, attentionTotal, blocked, capped,
        attentionTotal > items.size(), clock.instant());
  }

  @Transactional(readOnly = true)
  public OrderJourneyDetailDto detail(UUID id) {
    UUID tenantId = TenantContext.requireTenantId();
    OrderJourney journey = journeyRepository.findByIdAndTenantId(id, tenantId)
        .orElseThrow(() -> new NotFoundException("Order journey not found"));
    return toDetail(tenantId, journey, "READY");
  }

  @Transactional(readOnly = true)
  public OrderJourneyDetailDto detailByEntity(OrderJourney journey) {
    return toDetail(journey.getTenantId(), journey, "READY");
  }

  /** OP-CAP-23 — detail for a freshly-materialized journey, tagged with how the projection was obtained. */
  @Transactional(readOnly = true)
  public OrderJourneyDetailDto detailByEntity(OrderJourney journey, String projectionSource) {
    return toDetail(journey.getTenantId(), journey, projectionSource);
  }

  /**
   * OP-CAP-23 — read the already-projected journey for a source WITHOUT materializing it. Empty when no
   * projection exists yet (the caller then decides whether to request one / use the read fallback).
   */
  @Transactional(readOnly = true)
  public java.util.Optional<OrderJourneyDetailDto> detailBySourceIfPresent(
      com.orderpilot.domain.journey.JourneySourceType sourceType, UUID sourceId) {
    UUID tenantId = TenantContext.requireTenantId();
    return journeyRepository.findByTenantIdAndSourceTypeAndSourceId(tenantId, sourceType, sourceId)
        .map(j -> toDetail(tenantId, j, "READY"));
  }

  @Transactional(readOnly = true)
  public CustomerSafeJourneyDto customerSafe(UUID id) {
    UUID tenantId = TenantContext.requireTenantId();
    OrderJourney journey = journeyRepository.findByIdAndTenantId(id, tenantId)
        .orElseThrow(() -> new NotFoundException("Order journey not found"));
    List<CustomerTrackingMilestoneDto> milestones = milestoneRepository
        .findByTenantIdAndJourneyIdOrderBySortOrderAsc(tenantId, id).stream()
        .filter(OrderJourneyMilestone::isCustomerVisible)
        .map(this::toCustomerMilestone)
        .toList();
    List<CustomerTrackingEventDto> events = eventRepository
        .findByTenantIdAndJourneyIdOrderByOccurredAtDesc(tenantId, id, PageRequest.of(0, EVENT_LIMIT)).stream()
        .filter(OrderJourneyEvent::isCustomerVisible)
        .map(this::toCustomerEvent)
        .toList();
    boolean fulfillmentConnected = milestones.stream()
        .anyMatch(m -> !"UNKNOWN".equals(m.evidenceLevel()) && !"NOT_STARTED".equals(m.milestoneState()));
    // Internal, permission-protected (ANALYTICS_READ) tenant-scoped API path — NOT a public secure
    // tracking link. The shareable public buyer link is the OP-CAP-46C secure tracking link
    // (GET /api/v1/public/order-tracking/{token}); this path stays internal/operator-facing.
    String customerSafeApiPath = "/api/v1/order-journeys/" + id + "/customer-safe";
    return new CustomerSafeJourneyDto(journey.getId(), journey.getCustomerVisibleStatus(), milestones, events,
        fulfillmentConnected, false, customerSafeApiPath, clock.instant());
  }

  /**
   * OP-CAP-46C — customer-safe tracking view for the PUBLIC secure-link path. The {@code tenantId} and
   * {@code journeyId} are resolved from the verified token by the caller; this method re-asserts the
   * scope with a tenant-scoped fetch (so a token can never reach another tenant's or journey's data)
   * and returns the redacted {@link PublicOrderTrackingView}. It exposes only customer-visible
   * milestones and the customer-visible status — no internal milestone/source/signal fields, no risk
   * level, no internal status, and no identifiers. Strictly read-only.
   */
  @Transactional(readOnly = true)
  public PublicOrderTrackingView publicTracking(UUID tenantId, UUID journeyId) {
    OrderJourney journey = journeyRepository.findByIdAndTenantId(journeyId, tenantId)
        .orElseThrow(() -> new NotFoundException("Order journey not found"));
    List<PublicTrackingMilestoneDto> milestones = milestoneRepository
        .findByTenantIdAndJourneyIdOrderBySortOrderAsc(tenantId, journeyId).stream()
        .filter(OrderJourneyMilestone::isCustomerVisible)
        .map(this::toPublicMilestone)
        .toList();
    boolean fulfillmentConnected = milestones.stream()
        .anyMatch(m -> !"UNKNOWN".equals(m.evidenceLevel()) && !"NOT_STARTED".equals(m.milestoneState()));
    return new PublicOrderTrackingView(journey.getCustomerVisibleStatus(), milestones, fulfillmentConnected,
        clock.instant());
  }

  private PublicTrackingMilestoneDto toPublicMilestone(OrderJourneyMilestone m) {
    return new PublicTrackingMilestoneDto(m.getMilestoneLabel(), m.getMilestoneState().name(),
        m.getEvidenceLevel().name(), m.getOccurredAt(), m.getEstimatedAt());
  }

  /**
   * OP-CAP-47A — operator fulfillment visibility timeline. Composes the existing tenant-scoped
   * OrderJourney projection summary with its ingested fulfillment signals as a deterministically
   * ordered, operator-safe timeline. Strictly read-only: no milestone/signal/order state is mutated
   * and no external write happens here.
   *
   * <p>Tenant isolation is enforced by the tenant-scoped journey fetch (a cross-tenant journey id is a
   * {@code NotFoundException}, matching the established convention) and by the tenant-scoped signal
   * query. Duplicate fulfillment signals are already collapsed at ingest (one row per tenant + journey
   * + source + type + sourceRef), so a replayed signal yields exactly one timeline entry. The response
   * never carries the signal's internal id, raw payload ref, external source/idempotency ref,
   * confidence, tenant id, or any audit/storage internals.
   */
  @Transactional(readOnly = true)
  public OperatorFulfillmentTimelineResponse operatorTimeline(UUID id) {
    UUID tenantId = TenantContext.requireTenantId();
    OrderJourney journey = journeyRepository.findByIdAndTenantId(id, tenantId)
        .orElseThrow(() -> new NotFoundException("Order journey not found"));

    List<FulfillmentSignal> signals = signalRepository
        .findByTenantIdAndJourneyIdOrderByReceivedAtAsc(tenantId, id, PageRequest.of(0, TIMELINE_SIGNAL_LIMIT))
        .stream()
        .sorted(TIMELINE_ORDER)
        .toList();

    List<OperatorTimelineEntry> timeline = new java.util.ArrayList<>(signals.size());
    int sequence = 1;
    Instant latestReceivedAt = null;
    boolean returnRequested = false;
    for (FulfillmentSignal s : signals) {
      timeline.add(toTimelineEntry(s, sequence++));
      if (latestReceivedAt == null || s.getReceivedAt().isAfter(latestReceivedAt)) {
        latestReceivedAt = s.getReceivedAt();
      }
      if (s.getSignalType() == FulfillmentSignalType.RETURN_REQUESTED) {
        returnRequested = true;
      }
    }

    return new OperatorFulfillmentTimelineResponse(
        journey.getId(), journey.getSourceType().name(), journey.getCurrentStage().name(),
        journey.getCurrentStatus(), journey.getRiskLevel(), journey.isBlocked(),
        signals.size(), latestReceivedAt, returnRequested, List.copyOf(timeline),
        journey.getCreatedAt(), journey.getUpdatedAt(), clock.instant());
  }

  private OperatorTimelineEntry toTimelineEntry(FulfillmentSignal s, int sequence) {
    String label = s.getSignalType().milestoneCode()
        .map(com.orderpilot.domain.journey.MilestoneCode::label)
        .orElseGet(() -> humanizeSignalType(s.getSignalType()));
    return new OperatorTimelineEntry(
        sequence, s.getSignalType().name(), label, s.getSignalStatus(),
        s.getSourceType().name(), s.getSourceType().evidenceLevel().name(),
        s.isCustomerVisible(), s.getReceivedAt(), s.getProcessedAt());
  }

  /**
   * Safe sentence-case fallback label for a signal type that advances no canonical milestone (e.g.
   * RETURN_REQUESTED → "Return requested"). Matches the milestone label style ("Ready to ship").
   */
  private static String humanizeSignalType(FulfillmentSignalType type) {
    String lower = type.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    if (lower.isEmpty()) return lower;
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  private OrderJourneyDetailDto toDetail(UUID tenantId, OrderJourney journey, String projectionSource) {
    List<OrderJourneyMilestone> milestones =
        milestoneRepository.findByTenantIdAndJourneyIdOrderBySortOrderAsc(tenantId, journey.getId());
    List<OrderJourneyEventDto> events = eventRepository
        .findByTenantIdAndJourneyIdOrderByOccurredAtDesc(tenantId, journey.getId(), PageRequest.of(0, EVENT_LIMIT))
        .stream().map(this::toEvent).toList();
    List<FulfillmentSignalDto> signals = signalRepository
        .findByTenantIdAndJourneyIdOrderByReceivedAtDesc(tenantId, journey.getId(), PageRequest.of(0, SIGNAL_LIMIT))
        .stream().map(this::toSignal).toList();
    boolean fulfillmentConnected = !signals.isEmpty()
        || milestones.stream().anyMatch(m -> m.getMilestoneCode().sortOrder() >= 110
            && m.getMilestoneState() == MilestoneState.COMPLETED
            && m.getEvidenceLevel() != EvidenceLevel.UNKNOWN);
    return new OrderJourneyDetailDto(
        journey.getId(), journey.getSourceType().name(), journey.getSourceId(), journey.getCustomerAccountId(),
        journey.getCustomerDisplayName(), journey.getCurrentStage().name(), journey.getCurrentStatus(),
        journey.getRiskLevel(), journey.isBlocked(), journey.getCustomerVisibleStatus(), journey.getInternalStatus(),
        journey.getLastSignalAt(), journey.getCreatedAt(), journey.getUpdatedAt(),
        milestones.stream().map(this::toMilestone).toList(), events, signals,
        false, fulfillmentConnected, projectionSource, clock.instant());
  }

  private OrderJourneyListItemDto toListItem(OrderJourney j) {
    // List rows carry the projection-level evidence (SYSTEM_DERIVED). Per-milestone evidence
    // (VERIFIED/MIRRORED/ESTIMATED/UNKNOWN) is exposed in the detail view to keep the list query cheap.
    return new OrderJourneyListItemDto(j.getId(), j.getSourceType().name(), j.getSourceId(),
        j.getCustomerAccountId(), j.getCustomerDisplayName(), j.getCurrentStage().name(), j.getCurrentStatus(),
        j.getRiskLevel(), j.isBlocked(), "SYSTEM_DERIVED", j.getLastSignalAt(), j.getUpdatedAt());
  }

  private OrderJourneyMilestoneDto toMilestone(OrderJourneyMilestone m) {
    return new OrderJourneyMilestoneDto(m.getMilestoneCode().name(), m.getMilestoneLabel(),
        m.getMilestoneState().name(), m.getEvidenceLevel().name(), m.getOccurredAt(), m.getEstimatedAt(),
        m.getSourceType(), m.getSourceRef(), m.isCustomerVisible(), m.getSortOrder());
  }

  private CustomerTrackingMilestoneDto toCustomerMilestone(OrderJourneyMilestone m) {
    return new CustomerTrackingMilestoneDto(m.getMilestoneCode().name(), m.getMilestoneLabel(),
        m.getMilestoneState().name(), m.getEvidenceLevel().name(), m.getOccurredAt(), m.getEstimatedAt());
  }

  private OrderJourneyEventDto toEvent(OrderJourneyEvent e) {
    return new OrderJourneyEventDto(e.getEventType(), e.getEventStatus(), e.getEvidenceLevel().name(),
        e.getMessage(), e.getSourceType(), e.getSourceRef(), e.getActorType().name(), e.isCustomerVisible(),
        e.getOccurredAt());
  }

  private CustomerTrackingEventDto toCustomerEvent(OrderJourneyEvent e) {
    return new CustomerTrackingEventDto(e.getEventType(), e.getEventStatus(), e.getEvidenceLevel().name(),
        e.getMessage(), e.getOccurredAt());
  }

  private FulfillmentSignalDto toSignal(FulfillmentSignal s) {
    return new FulfillmentSignalDto(s.getId(), s.getSourceType().name(), s.getSignalType().name(),
        s.getSignalStatus(), s.getConfidence(), s.getSourceRef(), s.isCustomerVisible(), s.getReceivedAt(),
        s.getProcessedAt());
  }

  private int clamp(int requested, int max) {
    if (requested <= 0) return max;
    return Math.min(requested, max);
  }
}
