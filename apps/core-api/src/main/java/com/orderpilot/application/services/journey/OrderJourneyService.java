package com.orderpilot.application.services.journey;

import com.orderpilot.api.dto.OrderJourneyDtos.RecordFulfillmentSignalRequest;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.EvidenceLevel;
import com.orderpilot.domain.journey.FulfillmentSignal;
import com.orderpilot.domain.journey.FulfillmentSignalRepository;
import com.orderpilot.domain.journey.FulfillmentSignalSource;
import com.orderpilot.domain.journey.FulfillmentSignalType;
import com.orderpilot.domain.journey.JourneyActorType;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.MilestoneCode;
import com.orderpilot.domain.journey.MilestoneState;
import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.domain.journey.OrderJourneyEvent;
import com.orderpilot.domain.journey.OrderJourneyEventRepository;
import com.orderpilot.domain.journey.OrderJourneyMilestone;
import com.orderpilot.domain.journey.OrderJourneyMilestoneRepository;
import com.orderpilot.domain.journey.OrderJourneyRepository;
import com.orderpilot.domain.journey.events.JourneyProjectionEventType;
import com.orderpilot.domain.reconciliation.ReconciliationCase;
import com.orderpilot.domain.reconciliation.ReconciliationCaseRepository;
import com.orderpilot.domain.reconciliation.ReconciliationStatus;
import com.orderpilot.domain.workspace.DraftOrder;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-22 — builds/refreshes a derived order journey from a trusted internal source and ingests
 * safe internal fulfillment signals. The journey is a rebuildable projection; the source object
 * stays the system of record. No external/connector/ERP/payment writes, no AI decisions. Every
 * explicit mutation (build/refresh, signal ingest) emits an audit event.
 *
 * <p>Status authority: VERIFIED &gt; MIRRORED &gt; SYSTEM_DERIVED &gt; ESTIMATED &gt; UNKNOWN. Payment
 * milestones are always derived as UNKNOWN — there is no payment mirror wired and they are never
 * fabricated. A fulfillment signal cannot advance a payment milestone (no payment signal type exists).
 */
@Service
public class OrderJourneyService {
  private static final Set<String> APPROVED_STATUSES =
      Set.of("APPROVED", "APPROVED_INTERNAL", "APPROVED_FOR_DRAFT", "APPROVED_FOR_NEXT_STEP");
  private static final Set<String> CONFIRMED_STATUSES =
      Set.of("CONFIRMED", "APPROVED", "APPROVED_INTERNAL");
  private static final Set<String> CANCELLED_STATUSES = Set.of("REJECTED", "CANCELLED");

  private final OrderJourneyRepository journeyRepository;
  private final OrderJourneyMilestoneRepository milestoneRepository;
  private final OrderJourneyEventRepository eventRepository;
  private final FulfillmentSignalRepository signalRepository;
  private final DraftQuoteRepository draftQuoteRepository;
  private final DraftOrderRepository draftOrderRepository;
  private final ExceptionCaseRepository exceptionCaseRepository;
  private final ReconciliationCaseRepository reconciliationCaseRepository;
  private final AuditEventService auditEventService;
  private final OrderJourneyProjectionPublisher journeyProjectionPublisher;
  private final Clock clock;

  public OrderJourneyService(OrderJourneyRepository journeyRepository,
      OrderJourneyMilestoneRepository milestoneRepository, OrderJourneyEventRepository eventRepository,
      FulfillmentSignalRepository signalRepository, DraftQuoteRepository draftQuoteRepository,
      DraftOrderRepository draftOrderRepository, ExceptionCaseRepository exceptionCaseRepository,
      ReconciliationCaseRepository reconciliationCaseRepository, AuditEventService auditEventService,
      OrderJourneyProjectionPublisher journeyProjectionPublisher, Clock clock) {
    this.journeyRepository = journeyRepository;
    this.milestoneRepository = milestoneRepository;
    this.eventRepository = eventRepository;
    this.signalRepository = signalRepository;
    this.draftQuoteRepository = draftQuoteRepository;
    this.draftOrderRepository = draftOrderRepository;
    this.exceptionCaseRepository = exceptionCaseRepository;
    this.reconciliationCaseRepository = reconciliationCaseRepository;
    this.auditEventService = auditEventService;
    this.journeyProjectionPublisher = journeyProjectionPublisher;
    this.clock = clock;
  }

  /** Idempotently build (if missing) or refresh the journey for a source, then return it. */
  @Transactional
  public OrderJourney ensureJourney(JourneySourceType sourceType, UUID sourceId) {
    return refreshFromSource(sourceType, sourceId);
  }

  /**
   * OP-CAP-23 — projector-safe refresh: returns empty (rather than throwing {@link NotFoundException}) when
   * the trusted internal source row is absent, so the event/outbox projector can mark the event SKIPPED
   * without poisoning the surrounding transaction. Presence is checked up-front with a tenant-scoped read.
   */
  @Transactional
  public java.util.Optional<OrderJourney> refreshIfSourcePresent(JourneySourceType sourceType, UUID sourceId) {
    UUID tenantId = TenantContext.requireTenantId();
    if (sourceId == null || !sourceType.isProjectable() || !sourcePresent(tenantId, sourceType, sourceId)) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(refreshFromSource(sourceType, sourceId));
  }

  private boolean sourcePresent(UUID tenantId, JourneySourceType sourceType, UUID sourceId) {
    return switch (sourceType) {
      case DRAFT_QUOTE -> draftQuoteRepository.findByIdAndTenantId(sourceId, tenantId).isPresent();
      case DRAFT_ORDER, ORDER -> draftOrderRepository.findByIdAndTenantId(sourceId, tenantId).isPresent();
      case VALIDATION_REVIEW -> exceptionCaseRepository.findByIdAndTenantId(sourceId, tenantId).isPresent();
      case RECONCILIATION_CASE -> reconciliationCaseRepository.findByIdAndTenantId(sourceId, tenantId).isPresent();
      case EXTERNAL_MIRROR -> false; // no persisted external source row this stage (out of scope)
    };
  }

  @Transactional
  public OrderJourney refreshFromSource(JourneySourceType sourceType, UUID sourceId) {
    UUID tenantId = TenantContext.requireTenantId();
    Instant now = clock.instant();
    SourceFacts facts = loadFacts(tenantId, sourceType, sourceId);

    OrderJourney journey = journeyRepository
        .findByTenantIdAndSourceTypeAndSourceId(tenantId, sourceType, sourceId)
        .orElseGet(() -> journeyRepository.save(
            new OrderJourney(tenantId, sourceType, sourceId, facts.customerAccountId(),
                facts.customerDisplayName(), facts.createdAt() != null ? facts.createdAt() : now)));

    List<FulfillmentSignal> signals =
        signalRepository.findByTenantIdAndJourneyIdOrderByReceivedAtAsc(tenantId, journey.getId());

    Map<MilestoneCode, Computed> computed = deriveMilestones(facts, signals);
    persistMilestones(tenantId, journey.getId(), computed, now);

    boolean blocked = facts.reconciliationBlocked()
        || signals.stream().anyMatch(s -> s.getSignalType().isBlocking());
    boolean cancelled = computed.get(MilestoneCode.CANCELLED).state() == MilestoneState.COMPLETED;
    MilestoneCode currentStage = resolveCurrentStage(computed, blocked, cancelled);
    String currentStatus = currentStage.label() + (blocked ? " — blocked" : "");
    String riskLevel = blocked ? "HIGH" : (facts.hasExceptionCase() ? "MEDIUM" : "LOW");
    String internalStatus = blocked ? currentStage.label() + " (operator attention required)" : currentStage.label();
    String customerVisibleStatus = resolveCustomerVisibleStatus(computed);
    Instant lastSignalAt = signals.isEmpty() ? null : signals.get(signals.size() - 1).effectiveAt();

    journey.applyState(currentStage, currentStatus, riskLevel, blocked, customerVisibleStatus,
        internalStatus, facts.customerAccountId(), facts.customerDisplayName(), lastSignalAt, now);
    journeyRepository.save(journey);

    eventRepository.save(new OrderJourneyEvent(tenantId, journey.getId(), "JOURNEY_REFRESHED", currentStage.name(),
        EvidenceLevel.SYSTEM_DERIVED, "Journey projection refreshed", sourceType.name(), sourceId.toString(),
        JourneyActorType.SYSTEM, null, false, now, now));
    auditEventService.record("ORDER_JOURNEY_REFRESHED", "ORDER_JOURNEY", journey.getId().toString(), null,
        "{\"stage\":\"" + currentStage.name() + "\",\"blocked\":" + blocked + "}");
    return journey;
  }

  /** Operator/internal fulfillment signal ingest. Audited. No external write. */
  @Transactional
  public OrderJourney recordSignal(UUID journeyId, RecordFulfillmentSignalRequest request, UUID actorId) {
    UUID tenantId = TenantContext.requireTenantId();
    Instant now = clock.instant();
    OrderJourney journey = journeyRepository.findByIdAndTenantId(journeyId, tenantId)
        .orElseThrow(() -> new NotFoundException("Order journey not found"));

    FulfillmentSignalSource source = parseSource(request.sourceType());
    FulfillmentSignalType type = parseType(request.signalType());
    boolean customerVisible = Boolean.TRUE.equals(request.customerVisible());

    // OP-CAP-46A idempotency: a replayed signal (same tenant + journey + source + type + stable
    // sourceRef) must not create a duplicate signal/event/audit or duplicate milestone effect. Keyed on
    // sourceRef when the caller supplies the stable reference for that physical fulfillment event.
    String sourceRef = request.sourceRef();
    if (sourceRef != null && !sourceRef.isBlank()
        && signalRepository.findFirstByTenantIdAndJourneyIdAndSourceTypeAndSignalTypeAndSourceRef(
            tenantId, journeyId, source, type, sourceRef).isPresent()) {
      return journey;
    }

    FulfillmentSignal savedSignal = signalRepository.save(new FulfillmentSignal(tenantId, journeyId, source, type,
        request.signalStatus(), request.confidence(), request.sourceRef(), request.rawPayloadRef(),
        customerVisible, now, now));

    JourneyActorType actorType = actorTypeFor(source);
    eventRepository.save(new OrderJourneyEvent(tenantId, journeyId, "FULFILLMENT_SIGNAL", type.name(),
        source.evidenceLevel(), "Fulfillment signal: " + type.name(), source.name(), request.sourceRef(),
        actorType, actorType == JourneyActorType.OPERATOR ? actorId : null, customerVisible, now, now));
    auditEventService.record("ORDER_JOURNEY_SIGNAL_RECORDED", "ORDER_JOURNEY", journeyId.toString(), actorId,
        "{\"signalType\":\"" + type.name() + "\",\"source\":\"" + source.name() + "\"}");

    // OP-CAP-24: publish a durable, idempotent journey projection event for the underlying journey source.
    // The discriminator is the new signal id, so distinct signals each get their own event while reprocessing
    // the same signal event is idempotent. The synchronous refresh below is preserved (OP-CAP-22 behavior).
    journeyProjectionPublisher.publishSourceEvent(tenantId, JourneyProjectionEventType.FULFILLMENT_SIGNAL_RECORDED,
        journey.getSourceType(), journey.getSourceId(), savedSignal.getId().toString());

    return refreshFromSource(journey.getSourceType(), journey.getSourceId());
  }

  // --- derivation ---------------------------------------------------------------------------------

  private record SourceFacts(Instant createdAt, String status, boolean hasValidationRun, boolean hasExceptionCase,
      Instant approvedAt, boolean isOrder, boolean reconciliationBlocked, UUID customerAccountId,
      String customerDisplayName) {}

  private record Computed(MilestoneState state, EvidenceLevel evidence, Instant occurredAt, String sourceRef) {}

  private SourceFacts loadFacts(UUID tenantId, JourneySourceType sourceType, UUID sourceId) {
    return switch (sourceType) {
      case DRAFT_QUOTE -> {
        DraftQuote q = draftQuoteRepository.findByIdAndTenantId(sourceId, tenantId)
            .orElseThrow(() -> new NotFoundException("Draft quote not found"));
        yield new SourceFacts(q.getCreatedAt(), q.getStatus(), q.getSourceValidationRunId() != null,
            q.getSourceExceptionCaseId() != null, q.getApprovedAt(), false, false,
            q.getCustomerAccountId(), q.getCustomerDisplayName());
      }
      case DRAFT_ORDER, ORDER -> {
        DraftOrder o = draftOrderRepository.findByIdAndTenantId(sourceId, tenantId)
            .orElseThrow(() -> new NotFoundException("Draft order not found"));
        yield new SourceFacts(o.getCreatedAt(), o.getStatus(), o.getSourceValidationRunId() != null,
            o.getSourceExceptionCaseId() != null, null, true, false, o.getCustomerAccountId(), null);
      }
      case VALIDATION_REVIEW -> {
        ExceptionCase c = exceptionCaseRepository.findByIdAndTenantId(sourceId, tenantId)
            .orElseThrow(() -> new NotFoundException("Validation review case not found"));
        yield new SourceFacts(c.getCreatedAt(), c.getStatus(), c.getValidationRunId() != null, true, null,
            false, false, c.getCustomerAccountId(), null);
      }
      case RECONCILIATION_CASE -> {
        ReconciliationCase rc = reconciliationCaseRepository.findByIdAndTenantId(sourceId, tenantId)
            .orElseThrow(() -> new NotFoundException("Reconciliation case not found"));
        boolean open = rc.getStatus() == ReconciliationStatus.OPEN;
        yield new SourceFacts(rc.getCreatedAt(), rc.getStatus().name(), false, true, null, false, open, null, null);
      }
      case EXTERNAL_MIRROR -> new SourceFacts(clock.instant(), "MIRRORED", false, false, null, true, false, null, null);
    };
  }

  private Map<MilestoneCode, Computed> deriveMilestones(SourceFacts facts, List<FulfillmentSignal> signals) {
    Map<MilestoneCode, Computed> map = new EnumMap<>(MilestoneCode.class);
    for (MilestoneCode code : MilestoneCode.values()) {
      map.put(code, new Computed(MilestoneState.NOT_STARTED, EvidenceLevel.UNKNOWN, null, null));
    }
    // Payment is explicitly UNKNOWN — never fabricated (no payment mirror domain wired).
    map.put(MilestoneCode.PAYMENT_PENDING, new Computed(MilestoneState.UNKNOWN, EvidenceLevel.UNKNOWN, null, null));
    map.put(MilestoneCode.PAYMENT_CONFIRMED, new Computed(MilestoneState.UNKNOWN, EvidenceLevel.UNKNOWN, null, null));

    Instant created = facts.createdAt();
    map.put(MilestoneCode.REQUEST_RECEIVED, new Computed(MilestoneState.COMPLETED, EvidenceLevel.SYSTEM_DERIVED, created, null));

    if (facts.hasValidationRun()) {
      map.put(MilestoneCode.VALIDATION_STARTED, new Computed(MilestoneState.COMPLETED, EvidenceLevel.SYSTEM_DERIVED, created, null));
      map.put(MilestoneCode.VALIDATION_COMPLETED, new Computed(MilestoneState.COMPLETED, EvidenceLevel.SYSTEM_DERIVED, created, null));
    } else if (facts.hasExceptionCase()) {
      map.put(MilestoneCode.VALIDATION_STARTED, new Computed(MilestoneState.ACTIVE, EvidenceLevel.SYSTEM_DERIVED, created, null));
    }

    if (facts.isOrder()) {
      map.put(MilestoneCode.ORDER_DRAFTED, new Computed(MilestoneState.COMPLETED, EvidenceLevel.SYSTEM_DERIVED, created, null));
      if (facts.status() != null && CONFIRMED_STATUSES.contains(facts.status())) {
        map.put(MilestoneCode.ORDER_CONFIRMED, new Computed(MilestoneState.COMPLETED, EvidenceLevel.SYSTEM_DERIVED, created, null));
      }
    } else {
      map.put(MilestoneCode.QUOTE_DRAFTED, new Computed(MilestoneState.COMPLETED, EvidenceLevel.SYSTEM_DERIVED, created, null));
    }

    if (facts.status() != null && APPROVED_STATUSES.contains(facts.status())) {
      Instant when = facts.approvedAt() != null ? facts.approvedAt() : created;
      map.put(MilestoneCode.QUOTE_APPROVED, new Computed(MilestoneState.COMPLETED, EvidenceLevel.MANUAL, when, null));
    }
    if (facts.status() != null && CANCELLED_STATUSES.contains(facts.status())) {
      map.put(MilestoneCode.CANCELLED, new Computed(MilestoneState.COMPLETED, EvidenceLevel.SYSTEM_DERIVED, created, null));
    }
    if (facts.reconciliationBlocked()) {
      map.put(MilestoneCode.BLOCKED_EXCEPTION, new Computed(MilestoneState.ACTIVE, EvidenceLevel.SYSTEM_DERIVED, created, "RECONCILIATION_CASE"));
    }

    // Fulfillment signals (chronological) advance their mapped milestone with the signal's evidence.
    for (FulfillmentSignal signal : signals) {
      EvidenceLevel evidence = signal.getSourceType().evidenceLevel();
      signal.getSignalType().milestoneCode().ifPresent(code -> {
        if (code == MilestoneCode.DELIVERED && !isTrustedDeliveryEvidence(evidence)) {
          // OP-CAP-46A forbidden path: a carrier/WMS-mirrored (or otherwise unverified) "delivered"
          // signal must NOT directly complete DELIVERED. It is surfaced as reported-but-unconfirmed
          // (ACTIVE / ESTIMATED) — only a VERIFIED internal event or an operator-attested MANUAL
          // signal may confirm delivery. A prior trusted confirmation is never downgraded.
          Computed existing = map.get(code);
          if (existing == null || existing.state() != MilestoneState.COMPLETED) {
            map.put(code, new Computed(MilestoneState.ACTIVE, EvidenceLevel.ESTIMATED, signal.effectiveAt(),
                signal.getSourceRef()));
          }
        } else {
          map.put(code, new Computed(MilestoneState.COMPLETED, evidence, signal.effectiveAt(),
              signal.getSourceRef()));
        }
      });
      if (signal.getSignalType().isBlocking()) {
        map.put(MilestoneCode.BLOCKED_EXCEPTION, new Computed(MilestoneState.ACTIVE,
            evidence, signal.effectiveAt(), signal.getSourceRef()));
      }
    }
    return map;
  }

  /**
   * OP-CAP-46A — DELIVERED is a high-stakes, near-terminal milestone. Only a VERIFIED internal event or
   * an operator-attested MANUAL signal may confirm it; MIRRORED/IMPORT/SYSTEM_DERIVED/ESTIMATED evidence
   * (e.g. an unverified carrier/WMS mirror) cannot directly mark an order delivered.
   */
  private static boolean isTrustedDeliveryEvidence(EvidenceLevel evidence) {
    return evidence == EvidenceLevel.VERIFIED || evidence == EvidenceLevel.MANUAL;
  }

  private void persistMilestones(UUID tenantId, UUID journeyId, Map<MilestoneCode, Computed> computed, Instant now) {
    milestoneRepository.deleteByTenantIdAndJourneyId(tenantId, journeyId);
    milestoneRepository.flush();
    List<OrderJourneyMilestone> rows = new ArrayList<>();
    for (MilestoneCode code : MilestoneCode.values()) {
      Computed c = computed.get(code);
      rows.add(new OrderJourneyMilestone(tenantId, journeyId, code, c.state(), c.evidence(), c.occurredAt(),
          null, null, c.sourceRef(), code.customerVisibleDefault(), now));
    }
    milestoneRepository.saveAll(rows);
  }

  private MilestoneCode resolveCurrentStage(Map<MilestoneCode, Computed> computed, boolean blocked, boolean cancelled) {
    if (cancelled) return MilestoneCode.CANCELLED;
    if (blocked) return MilestoneCode.BLOCKED_EXCEPTION;
    MilestoneCode stage = MilestoneCode.REQUEST_RECEIVED;
    for (MilestoneCode code : MilestoneCode.values()) {
      if (code == MilestoneCode.CANCELLED || code == MilestoneCode.BLOCKED_EXCEPTION) continue;
      if (computed.get(code).state() == MilestoneState.COMPLETED && code.sortOrder() > stage.sortOrder()) {
        stage = code;
      }
    }
    return stage;
  }

  private String resolveCustomerVisibleStatus(Map<MilestoneCode, Computed> computed) {
    String status = "Received";
    for (MilestoneCode code : MilestoneCode.values()) {
      if (code == MilestoneCode.BLOCKED_EXCEPTION) continue; // never reveal internal block to customer
      if (code.customerVisibleDefault() && computed.get(code).state() == MilestoneState.COMPLETED) {
        status = code.label();
      }
    }
    return status;
  }

  private FulfillmentSignalSource parseSource(String raw) {
    try {
      return FulfillmentSignalSource.valueOf(raw == null ? "" : raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unsupported fulfillment signal source: " + raw);
    }
  }

  private FulfillmentSignalType parseType(String raw) {
    try {
      return FulfillmentSignalType.valueOf(raw == null ? "" : raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unsupported fulfillment signal type: " + raw);
    }
  }

  private JourneyActorType actorTypeFor(FulfillmentSignalSource source) {
    return switch (source) {
      case INTERNAL, MANUAL -> JourneyActorType.OPERATOR;
      case CONNECTOR_MIRROR -> JourneyActorType.CONNECTOR;
      case IMPORT -> JourneyActorType.IMPORT;
      case SYSTEM_DERIVED -> JourneyActorType.SYSTEM;
    };
  }
}
