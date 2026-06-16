package com.orderpilot.application.services.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyDetailDto;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.JourneyProjectionHealthDto;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.OrderJourneyProjectionDrainSummary;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.OrderJourneyProjectionRecoverySummary;
import com.orderpilot.application.services.journey.OrderJourneyProjectionPublisher.PublishCommand;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.OrderJourneyRepository;
import com.orderpilot.domain.journey.events.JourneyProjectionEventStatus;
import com.orderpilot.domain.journey.events.JourneyProjectionEventType;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionCheckpointRepository;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEvent;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEventRepository;
import com.orderpilot.domain.workspace.DraftOrder;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-25 Controlled Journey Projector Drain Runtime — proves the drain turns pending events into READY
 * projections without a manual {@code projection/process} call, is bounded by tenant and per-tenant limits,
 * is idempotent, isolates per-tenant failures, never retries DEAD_LETTERED events, reports bounded health,
 * and leaves by-source READY (not ON_READ_FALLBACK). No fabricated payment/carrier/GPS state. Uses
 * {@code @SpringBootTest} because each event is processed in its own {@code REQUIRES_NEW} transaction (same
 * pattern as the OP-CAP-23 projector test); every test uses fresh random tenant ids for isolation.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderJourneyProjectionDrainServiceTest {
  private static final Instant NOW = Instant.parse("2026-06-15T00:00:00Z");

  @Autowired private org.springframework.context.ApplicationContext applicationContext;
  @Autowired private OrderJourneyProjectionDrainService drainService;
  @Autowired private OrderJourneyProjectionPublisher publisher;
  @Autowired private OrderJourneyProjectorRunner runner;
  @Autowired private OrderJourneyReadService readService;
  @Autowired private OrderJourneyProjectionEventRepository events;
  @Autowired private OrderJourneyProjectionCheckpointRepository checkpoints;
  @Autowired private OrderJourneyRepository journeyRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private DraftOrderRepository draftOrderRepository;

  /**
   * The cross-tenant drain ({@code drainTenants}/{@code pendingTenantCount}) scans every tenant in the shared
   * test database, so leftover PENDING events from sibling {@code @SpringBootTest} classes (which reuse this
   * cached context/DB) would otherwise contaminate the cross-tenant assertions. Clear projection events +
   * checkpoints before each test so cross-tenant counts are deterministic. Tenant-scoped tests are unaffected.
   */
  @BeforeEach
  void cleanSlate() {
    checkpoints.deleteAll();
    events.deleteAll();
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // ----------------------------- fixtures -----------------------------

  private UUID draftQuote(UUID tenantId, String number, String status) {
    return draftQuoteRepository.save(new DraftQuote(tenantId, number, UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), status, "USD", null, NOW)).getId();
  }

  private UUID draftOrder(UUID tenantId, String number, String status) {
    return draftOrderRepository.save(new DraftOrder(tenantId, number, UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), status, "USD", null, NOW)).getId();
  }

  private OrderJourneyProjectionEvent publish(UUID tenantId, JourneyProjectionEventType type,
      JourneySourceType sourceType, UUID sourceId, String idemKey) {
    return publisher.publish(new PublishCommand(tenantId, type, sourceType, sourceId, "TEST", null, null,
        idemKey, "summary", NOW));
  }

  private OrderJourneyProjectionEvent reload(UUID tenantId, UUID eventId) {
    return events.findByIdAndTenantId(eventId, tenantId).orElseThrow();
  }

  private Instant staleAfter(OrderJourneyProjectionEvent event) {
    return event.getCreatedAt().plusSeconds(OrderJourneyProjectionDrainService.DEFAULT_STALE_PROCESSING_SECONDS + 1);
  }

  // ----------------------------- 1. drain processes pending draft quote event -> READY -----------------------------

  @Test
  void drainTenantProcessesPendingDraftQuoteEventAndProducesReadyJourney() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-DR", "APPROVED");
    publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE, quoteId,
        "drain-q");

    OrderJourneyProjectionDrainSummary summary = drainService.drainTenant(tenantId, 25);

    assertThat(summary.tenantsScanned()).isEqualTo(1);
    assertThat(summary.eventsProcessed()).isEqualTo(1);
    OrderJourneyDetailDto detail = readService
        .detailBySourceIfPresent(JourneySourceType.DRAFT_QUOTE, quoteId).orElseThrow();
    assertThat(detail.projectionSource()).isEqualTo("READY");
    assertThat(detail.milestones()).anyMatch(m -> m.milestoneCode().equals("QUOTE_DRAFTED")
        && m.milestoneState().equals("COMPLETED"));
  }

  // ----------------------------- 2. drain processes pending draft order event -> READY -----------------------------

  @Test
  void drainTenantProcessesPendingDraftOrderEventAndProducesReadyJourney() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID orderId = draftOrder(tenantId, "O-DR", "CONFIRMED");
    publish(tenantId, JourneyProjectionEventType.DRAFT_ORDER_CREATED, JourneySourceType.DRAFT_ORDER, orderId,
        "drain-o");

    drainService.drainTenant(tenantId, 25);

    OrderJourneyDetailDto detail = readService
        .detailBySourceIfPresent(JourneySourceType.DRAFT_ORDER, orderId).orElseThrow();
    assertThat(detail.projectionSource()).isEqualTo("READY");
    assertThat(detail.milestones()).anyMatch(m -> m.milestoneCode().equals("ORDER_DRAFTED")
        && m.milestoneState().equals("COMPLETED"));
  }

  // ----------------------------- 3a. perTenantLimit bounds the batch -----------------------------

  @Test
  void drainTenantIsBoundedByPerTenantLimit() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    for (int i = 0; i < 3; i++) {
      UUID quoteId = draftQuote(tenantId, "Q-LIM-" + i, "APPROVED");
      publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE,
          quoteId, "lim-" + i);
    }

    OrderJourneyProjectionDrainSummary summary = drainService.drainTenant(tenantId, 2);

    assertThat(summary.limitApplied()).isEqualTo(2);
    assertThat(summary.eventsProcessed()).isEqualTo(2);
    assertThat(events.countByTenantIdAndStatus(tenantId, JourneyProjectionEventStatus.PENDING)).isEqualTo(1);
  }

  // ----------------------------- 3b. tenantLimit bounds the cross-tenant scan -----------------------------

  @Test
  void drainTenantsIsBoundedByTenantLimitAndReportsPartial() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID tenantC = UUID.randomUUID();
    for (UUID t : new UUID[] {tenantA, tenantB, tenantC}) {
      UUID quoteId = draftQuote(t, "Q-MT", "APPROVED");
      publish(t, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE, quoteId,
          "mt-" + t);
    }

    OrderJourneyProjectionDrainSummary summary = drainService.drainTenants(2, 50);

    // only two tenants visited this cycle; partial flags that more tenants remain
    assertThat(summary.tenantsScanned()).isEqualTo(2);
    assertThat(summary.partial()).isTrue();
    assertThat(summary.eventsProcessed()).isEqualTo(2);
  }

  // ----------------------------- 4. duplicate drain is idempotent -----------------------------

  @Test
  void duplicateDrainDoesNotDuplicateJourneyOrMilestones() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-IDEM", "DRAFT");
    publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE, quoteId,
        "idem-d");

    drainService.drainTenant(tenantId, 25);
    OrderJourneyProjectionDrainSummary second = drainService.drainTenant(tenantId, 25);

    // the event is already terminal — second drain processes nothing new
    assertThat(second.eventsProcessed()).isZero();
    assertThat(journeyRepository.countByTenantId(tenantId)).isEqualTo(1);
    OrderJourneyDetailDto detail = readService
        .detailBySourceIfPresent(JourneySourceType.DRAFT_QUOTE, quoteId).orElseThrow();
    long requestReceived = detail.milestones().stream()
        .filter(m -> m.milestoneCode().equals("REQUEST_RECEIVED")).count();
    assertThat(requestReceived).isEqualTo(1);
  }

  // ----------------------------- 5. one failing tenant does not block another -----------------------------

  @Test
  void aFailingEventInOneTenantDoesNotBlockAnotherTenantInTheSameCycle() {
    UUID healthy = UUID.randomUUID();
    UUID broken = UUID.randomUUID();
    UUID quoteId = draftQuote(healthy, "Q-OK", "APPROVED");
    publish(healthy, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE, quoteId,
        "ok-1");
    // projectable source type with a null sourceId -> projector throws -> bounded FAILED (not a thrown drain)
    UUID brokenEventId = publish(broken, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, null, "broken-1").getId();

    OrderJourneyProjectionDrainSummary summary = drainService.drainTenants(10, 50);

    assertThat(summary.tenantsScanned()).isEqualTo(2);
    assertThat(summary.eventsProcessed()).isEqualTo(1);
    assertThat(summary.eventsFailed()).isEqualTo(1);
    // healthy tenant still got its READY journey
    TenantContext.setTenantId(healthy);
    assertThat(readService.detailBySourceIfPresent(JourneySourceType.DRAFT_QUOTE, quoteId)
        .orElseThrow().projectionSource()).isEqualTo("READY");
    // broken event is FAILED (bounded), not dead — and certainly never blocked the healthy tenant
    assertThat(reload(broken, brokenEventId).getStatus()).isEqualTo(JourneyProjectionEventStatus.FAILED);
  }

  // ----------------------------- 6. invalid event becomes FAILED -----------------------------

  @Test
  void invalidEventBecomesBoundedFailedNotProcessed() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID eventId = publish(tenantId, JourneyProjectionEventType.ORDER_JOURNEY_REFRESH_REQUESTED,
        JourneySourceType.DRAFT_QUOTE, null, "invalid-d").getId();

    OrderJourneyProjectionDrainSummary summary = drainService.drainTenant(tenantId, 25);

    assertThat(summary.eventsFailed()).isEqualTo(1);
    assertThat(reload(tenantId, eventId).getStatus()).isEqualTo(JourneyProjectionEventStatus.FAILED);
    assertThat(reload(tenantId, eventId).getFailureCode()).isEqualTo("INVALID_PAYLOAD");
  }

  // ----------------------------- 7 & 8. DEAD_LETTERED excluded from discovery and never retried -----------------------------

  @Test
  void deadLetteredEventIsNeitherDiscoveredNorRetried() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourneyProjectionEvent event = publish(tenantId,
        JourneyProjectionEventType.ORDER_JOURNEY_REFRESH_REQUESTED, JourneySourceType.DRAFT_QUOTE, null,
        "dead-d");
    event.markDeadLettered("INVALID_PAYLOAD", "exhausted", NOW);
    events.save(event);

    // discovery (tenant ids with drainable work) must not include this tenant — its only event is dead
    assertThat(drainService.pendingTenantCount()).isZero();
    OrderJourneyProjectionDrainSummary summary = drainService.drainTenant(tenantId, 25);
    assertThat(summary.eventsProcessed()).isZero();
    assertThat(summary.eventsFailed()).isZero();
    assertThat(reload(tenantId, event.getId()).getStatus())
        .isEqualTo(JourneyProjectionEventStatus.DEAD_LETTERED);
  }

  // ----------------------------- 9. health reports bounded runtime status -----------------------------

  @Test
  void healthReportsPendingOldestSchedulerAndConfiguredBatch() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-H", "APPROVED");
    publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE, quoteId,
        "health-d");

    JourneyProjectionHealthDto health = runner.health(tenantId);

    assertThat(health.pendingEvents()).isEqualTo(1);
    assertThat(health.oldestPendingAt()).isEqualTo(NOW);
    assertThat(health.deadLetteredEvents()).isZero();
    // scheduler is disabled by default in the test profile; batch is the clamped configured default
    assertThat(health.schedulerEnabled()).isFalse();
    assertThat(health.configuredBatchSize()).isEqualTo(25);
  }

  // ----------------------------- 10. by-source after drain is READY, not ON_READ_FALLBACK -----------------------------

  @Test
  void bySourceAfterDrainReturnsReadyNotOnReadFallback() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-BS", "DRAFT");
    publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE, quoteId,
        "bs-d");

    drainService.drainTenant(tenantId, 25);

    OrderJourneyDetailDto detail = readService
        .detailBySourceIfPresent(JourneySourceType.DRAFT_QUOTE, quoteId).orElseThrow();
    assertThat(detail.projectionSource()).isEqualTo("READY");
    assertThat(detail.projectionSource()).isNotEqualTo("ON_READ_FALLBACK");
  }

  // ----------------------------- 12. scheduler disabled by default (no daemon) -----------------------------

  @Test
  void scheduledDrainBeanIsAbsentByDefault() {
    // the default (test/prod) posture: no scheduled drain bean, so no background processing exists
    assertThat(applicationContext.getBeanNamesForType(OrderJourneyProjectionScheduledDrain.class)).isEmpty();
  }

  // ----------------------------- 11. no fabricated payment state after drain -----------------------------

  @Test
  void drainNeverFabricatesPaymentMilestones() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-PAY", "APPROVED");
    publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE, quoteId,
        "pay-d");

    drainService.drainTenant(tenantId, 25);

    OrderJourneyDetailDto detail = readService
        .detailBySourceIfPresent(JourneySourceType.DRAFT_QUOTE, quoteId).orElseThrow();
    assertThat(detail.paymentStatusAvailable()).isFalse();
    assertThat(detail.milestones()).anyMatch(m -> m.milestoneCode().equals("PAYMENT_CONFIRMED")
        && m.milestoneState().equals("UNKNOWN"));
  }

  // ----------------------------- OP-CAP-26 missed-event recovery -----------------------------

  @Test
  void recoverMissedEventsProcessesPendingEventsInBoundedBatch() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    for (int i = 0; i < 3; i++) {
      UUID quoteId = draftQuote(tenantId, "Q-REC-P-" + i, "APPROVED");
      publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE,
          quoteId, "rec-p-" + i);
    }

    OrderJourneyProjectionRecoverySummary summary =
        drainService.recoverMissedEvents(tenantId, 2, Instant.now());

    assertThat(summary.scannedCount()).isEqualTo(2);
    assertThat(summary.recoveredCount()).isEqualTo(2);
    assertThat(summary.limitApplied()).isEqualTo(2);
    assertThat(events.countByTenantIdAndStatus(tenantId, JourneyProjectionEventStatus.PENDING)).isEqualTo(1);
  }

  @Test
  void recoveryDoesNotReprocessCompletedEventsAndIsIdempotent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-REC-IDEM", "APPROVED");
    publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE,
        quoteId, "rec-idem");

    OrderJourneyProjectionRecoverySummary first =
        drainService.recoverMissedEvents(tenantId, 25, Instant.now());
    OrderJourneyProjectionRecoverySummary second =
        drainService.recoverMissedEvents(tenantId, 25, Instant.now());

    assertThat(first.recoveredCount()).isEqualTo(1);
    assertThat(second.scannedCount()).isZero();
    assertThat(second.recoveredCount()).isZero();
    assertThat(journeyRepository.countByTenantId(tenantId)).isEqualTo(1);
  }

  @Test
  void recoveryReclaimsStaleProcessingEvents() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-REC-ST", "APPROVED");
    OrderJourneyProjectionEvent event = publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, quoteId, "rec-stale");
    event.markProcessing();
    events.save(event);

    OrderJourneyProjectionRecoverySummary summary =
        drainService.recoverMissedEvents(tenantId, 25, staleAfter(event));

    assertThat(summary.staleInProgressCount()).isEqualTo(1);
    assertThat(summary.recoveredCount()).isEqualTo(1);
    assertThat(reload(tenantId, event.getId()).getStatus()).isEqualTo(JourneyProjectionEventStatus.PROCESSED);
  }

  @Test
  void recoveryDoesNotReclaimFreshProcessingEvents() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-REC-FRESH", "APPROVED");
    OrderJourneyProjectionEvent event = publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, quoteId, "rec-fresh");
    event.markProcessing();
    events.save(event);

    OrderJourneyProjectionRecoverySummary summary =
        drainService.recoverMissedEvents(tenantId, 25, event.getCreatedAt().plusSeconds(1));

    assertThat(summary.scannedCount()).isZero();
    assertThat(reload(tenantId, event.getId()).getStatus()).isEqualTo(JourneyProjectionEventStatus.PROCESSING);
  }

  @Test
  void recoveryRetriesRetryableFailedEvents() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-REC-FAIL", "APPROVED");
    OrderJourneyProjectionEvent event = publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, quoteId, "rec-retry");
    Instant retryAt = event.getCreatedAt().minusSeconds(1);
    event.recordFailure("TRANSIENT", "safe bounded failure", retryAt, retryAt);
    events.save(event);

    OrderJourneyProjectionRecoverySummary summary =
        drainService.recoverMissedEvents(tenantId, 25, event.getCreatedAt());

    assertThat(summary.retryScheduledCount()).isEqualTo(1);
    assertThat(summary.recoveredCount()).isEqualTo(1);
    assertThat(reload(tenantId, event.getId()).getStatus()).isEqualTo(JourneyProjectionEventStatus.PROCESSED);
  }

  @Test
  void recoveryDoesNotRetryPermanentFailedEvents() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-REC-PERM", "APPROVED");
    OrderJourneyProjectionEvent event = publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, quoteId, "rec-perm");
    Instant retryAt = event.getCreatedAt().minusSeconds(1);
    event.recordFailure("TRANSIENT", "first", retryAt, retryAt);
    event.recordFailure("TRANSIENT", "second", retryAt, retryAt);
    event.recordFailure("TRANSIENT", "third", retryAt, retryAt);
    events.save(event);

    OrderJourneyProjectionRecoverySummary summary =
        drainService.recoverMissedEvents(tenantId, 25, event.getCreatedAt());

    assertThat(summary.scannedCount()).isZero();
    assertThat(reload(tenantId, event.getId()).getStatus()).isEqualTo(JourneyProjectionEventStatus.FAILED);
    assertThat(reload(tenantId, event.getId()).getRetryCount())
        .isEqualTo(OrderJourneyProjectionPublisher.MAX_RETRY);
  }

  @Test
  void recoveryIsTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID quoteA = draftQuote(tenantA, "Q-REC-A", "APPROVED");
    UUID quoteB = draftQuote(tenantB, "Q-REC-B", "APPROVED");
    publish(tenantA, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE,
        quoteA, "rec-a");
    publish(tenantB, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE,
        quoteB, "rec-b");

    OrderJourneyProjectionRecoverySummary summary =
        drainService.recoverMissedEvents(tenantA, 25, Instant.now());

    assertThat(summary.recoveredCount()).isEqualTo(1);
    assertThat(events.countByTenantIdAndStatus(tenantA, JourneyProjectionEventStatus.PENDING)).isZero();
    assertThat(events.countByTenantIdAndStatus(tenantB, JourneyProjectionEventStatus.PENDING)).isEqualTo(1);
    assertThat(journeyRepository.countByTenantId(tenantB)).isZero();
  }

  // ----------------------------- OP-CAP-26 drain health monitoring -----------------------------

  @Test
  void healthReportsProjectionDrainCountsAndOldestPendingAge() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-REC-H", "APPROVED");
    OrderJourneyProjectionEvent pending = publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, quoteId, "rec-health");
    OrderJourneyProjectionEvent stale = publish(tenantId,
        JourneyProjectionEventType.ORDER_JOURNEY_REFRESH_REQUESTED, JourneySourceType.DRAFT_QUOTE,
        quoteId, "rec-health-stale");
    stale.markProcessing();
    events.save(stale);

    JourneyProjectionHealthDto health = drainService.health(tenantId);

    assertThat(health.pendingEvents()).isEqualTo(1);
    assertThat(health.inProgressEvents()).isEqualTo(1);
    assertThat(health.oldestPendingAt()).isEqualTo(pending.getOccurredAt());
    assertThat(health.oldestPendingAgeSeconds()).isNotNull();
    assertThat(health.lastDrainErrorMessageSafe()).isNull();
  }

  @Test
  void healthReportsLastCheckpointAndRecoverySummary() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-REC-CHK", "APPROVED");
    OrderJourneyProjectionEvent event = publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, quoteId, "rec-checkpoint");

    drainService.recoverMissedEvents(tenantId, 25, Instant.now());
    JourneyProjectionHealthDto health = drainService.health(tenantId);

    assertThat(health.lastProcessedEventId()).isEqualTo(event.getId());
    assertThat(health.lastCheckpointEventId()).isEqualTo(event.getId());
    assertThat(health.lastCheckpointStatus()).isEqualTo("COMPLETED");
    assertThat(health.lastRecoveredAt()).isNotNull();
    assertThat(health.lastRecoveryRecoveredCount()).isEqualTo(1);
  }

  @Test
  void healthIsTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID quoteA = draftQuote(tenantA, "Q-REC-HA", "APPROVED");
    UUID quoteB = draftQuote(tenantB, "Q-REC-HB", "APPROVED");
    publish(tenantA, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE,
        quoteA, "rec-ha");
    publish(tenantB, JourneyProjectionEventType.DRAFT_QUOTE_CREATED, JourneySourceType.DRAFT_QUOTE,
        quoteB, "rec-hb");
    drainService.recoverMissedEvents(tenantA, 25, Instant.now());

    JourneyProjectionHealthDto healthA = drainService.health(tenantA);
    JourneyProjectionHealthDto healthB = drainService.health(tenantB);

    assertThat(healthA.pendingEvents()).isZero();
    assertThat(healthB.pendingEvents()).isEqualTo(1);
    assertThat(healthB.lastProcessedEventId()).isNull();
  }
}
