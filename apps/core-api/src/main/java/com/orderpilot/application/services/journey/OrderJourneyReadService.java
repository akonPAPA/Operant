package com.orderpilot.application.services.journey;

import com.orderpilot.api.dto.OrderJourneyDtos.CustomerSafeJourneyDto;
import com.orderpilot.api.dto.OrderJourneyDtos.FulfillmentSignalDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyAttentionSummaryDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyDetailDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyEventDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyListItemDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyMilestoneDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneySummaryDto;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.EvidenceLevel;
import com.orderpilot.domain.journey.FulfillmentSignal;
import com.orderpilot.domain.journey.FulfillmentSignalRepository;
import com.orderpilot.domain.journey.MilestoneState;
import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.domain.journey.OrderJourneyEvent;
import com.orderpilot.domain.journey.OrderJourneyEventRepository;
import com.orderpilot.domain.journey.OrderJourneyMilestone;
import com.orderpilot.domain.journey.OrderJourneyMilestoneRepository;
import com.orderpilot.domain.journey.OrderJourneyRepository;
import java.time.Clock;
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
    List<OrderJourneyMilestoneDto> milestones = milestoneRepository
        .findByTenantIdAndJourneyIdOrderBySortOrderAsc(tenantId, id).stream()
        .filter(OrderJourneyMilestone::isCustomerVisible)
        .map(this::toMilestone)
        .toList();
    List<OrderJourneyEventDto> events = eventRepository
        .findByTenantIdAndJourneyIdOrderByOccurredAtDesc(tenantId, id, PageRequest.of(0, EVENT_LIMIT)).stream()
        .filter(OrderJourneyEvent::isCustomerVisible)
        .map(this::toEvent)
        .toList();
    boolean fulfillmentConnected = milestones.stream()
        .anyMatch(m -> !"UNKNOWN".equals(m.evidenceLevel()) && !"NOT_STARTED".equals(m.milestoneState()));
    // Internal, permission-protected (ANALYTICS_READ) tenant-scoped API path — NOT a public secure
    // tracking link. A public, signed/expiring buyer tracking gateway is deferred to OP-CAP-46C.
    String customerSafeApiPath = "/api/v1/order-journeys/" + id + "/customer-safe";
    return new CustomerSafeJourneyDto(journey.getId(), journey.getCustomerVisibleStatus(), milestones, events,
        fulfillmentConnected, false, customerSafeApiPath, clock.instant());
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

  private OrderJourneyEventDto toEvent(OrderJourneyEvent e) {
    return new OrderJourneyEventDto(e.getEventType(), e.getEventStatus(), e.getEvidenceLevel().name(),
        e.getMessage(), e.getSourceType(), e.getSourceRef(), e.getActorType().name(), e.isCustomerVisible(),
        e.getOccurredAt());
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
