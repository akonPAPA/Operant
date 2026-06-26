package com.orderpilot.application.services.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.OrderJourneyDtos.OperatorFulfillmentTimelineResponse;
import com.orderpilot.api.dto.OrderJourneyDtos.OperatorTimelineEntry;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyDetailDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyMilestoneDto;
import com.orderpilot.api.dto.OrderJourneyDtos.RecordFulfillmentSignalRequest;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.FulfillmentSignal;
import com.orderpilot.domain.journey.FulfillmentSignalRepository;
import com.orderpilot.domain.journey.FulfillmentSignalSource;
import com.orderpilot.domain.journey.FulfillmentSignalType;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-47A — operator fulfillment visibility timeline: safe deterministic composition, empty-journey
 * handling, tenant isolation, idempotent collapse of duplicate signals, return-path non-mutation, and a
 * serialized-response leak guard. Strictly a read surface — it mutates no journey/milestone/signal state.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrderJourneyService.class, OrderJourneyReadService.class, OrderJourneyProjectionPublisher.class,
    AuditEventService.class, CoreConfiguration.class})
class OrderJourneyOperatorTimelineServiceTest {
  @Autowired private OrderJourneyService service;
  @Autowired private OrderJourneyReadService readService;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private FulfillmentSignalRepository signalRepository;

  private static final Instant NOW = Instant.parse("2026-06-20T00:00:00Z");
  private static final Instant T0 = Instant.parse("2026-06-20T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-06-20T11:00:00Z");
  private static final Instant T2 = Instant.parse("2026-06-20T12:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private OrderJourney newJourney(UUID tenantId, String quoteNumber, String status) {
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, quoteNumber, UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), status, "USD", null, NOW)).getId();
    return service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);
  }

  private FulfillmentSignal saveSignal(UUID tenantId, UUID journeyId, FulfillmentSignalSource source,
      FulfillmentSignalType type, String status, String sourceRef, String rawPayloadRef, boolean visible,
      Instant receivedAt) {
    return signalRepository.save(new FulfillmentSignal(tenantId, journeyId, source, type, status,
        new BigDecimal("0.950"), sourceRef, rawPayloadRef, visible, receivedAt, receivedAt));
  }

  @Test
  void normalTimelineReturnsSafeDeterministicallyOrderedEntries() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newJourney(tenantId, "Q-T1", "APPROVED");

    // Inserted out of receivedAt order on purpose; the timeline must order by receivedAt ascending.
    saveSignal(tenantId, journey.getId(), FulfillmentSignalSource.INTERNAL, FulfillmentSignalType.SHIPPED,
        "SHIPPED", "wh-3", null, true, T2);
    saveSignal(tenantId, journey.getId(), FulfillmentSignalSource.INTERNAL, FulfillmentSignalType.PACKED,
        "OK", "wh-1", null, true, T0);
    saveSignal(tenantId, journey.getId(), FulfillmentSignalSource.CONNECTOR_MIRROR,
        FulfillmentSignalType.READY_TO_SHIP, "READY", "wh-2", null, false, T1);

    OperatorFulfillmentTimelineResponse resp = readService.operatorTimeline(journey.getId());

    assertThat(resp.journeyId()).isEqualTo(journey.getId());
    assertThat(resp.currentStatus()).isNotBlank();
    assertThat(resp.signalCount()).isEqualTo(3);
    assertThat(resp.latestSignalReceivedAt()).isEqualTo(T2);
    assertThat(resp.returnRequested()).isFalse();

    assertThat(resp.timeline()).extracting(OperatorTimelineEntry::sequence).containsExactly(1, 2, 3);
    assertThat(resp.timeline()).extracting(OperatorTimelineEntry::receivedAt).containsExactly(T0, T1, T2);
    assertThat(resp.timeline()).extracting(OperatorTimelineEntry::type)
        .containsExactly("PACKED", "READY_TO_SHIP", "SHIPPED");
    assertThat(resp.timeline()).extracting(OperatorTimelineEntry::label)
        .containsExactly("Packed", "Ready to ship", "Shipped");
    // safe source/evidence classification surfaces honestly (verified internal vs mirrored connector)
    assertThat(resp.timeline().get(0).sourceType()).isEqualTo("INTERNAL");
    assertThat(resp.timeline().get(0).evidenceLevel()).isEqualTo("VERIFIED");
    assertThat(resp.timeline().get(1).sourceType()).isEqualTo("CONNECTOR_MIRROR");
    assertThat(resp.timeline().get(1).evidenceLevel()).isEqualTo("MIRRORED");
    assertThat(resp.timeline().get(1).customerVisible()).isFalse();
  }

  @Test
  void emptyJourneyReturnsValidEmptyTimelineNotError() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newJourney(tenantId, "Q-E1", "DRAFT");

    OperatorFulfillmentTimelineResponse resp = readService.operatorTimeline(journey.getId());

    assertThat(resp.journeyId()).isEqualTo(journey.getId());
    assertThat(resp.currentStatus()).isNotBlank();
    assertThat(resp.signalCount()).isZero();
    assertThat(resp.latestSignalReceivedAt()).isNull();
    assertThat(resp.returnRequested()).isFalse();
    assertThat(resp.timeline()).isEmpty();
  }

  @Test
  void crossTenantTimelineIsNotFound() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    OrderJourney journeyA = newJourney(tenantA, "Q-A1", "APPROVED");
    saveSignal(tenantA, journeyA.getId(), FulfillmentSignalSource.INTERNAL, FulfillmentSignalType.PACKED,
        "OK", "wh-a", null, true, T0);

    // Tenant B must not be able to read Tenant A's timeline.
    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> readService.operatorTimeline(journeyA.getId()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void sameSourceRefAcrossTenantsDoesNotCollide() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    String sharedRef = "carrier-key-shared-123";

    TenantContext.setTenantId(tenantA);
    OrderJourney journeyA = newJourney(tenantA, "Q-XA", "APPROVED");
    saveSignal(tenantA, journeyA.getId(), FulfillmentSignalSource.CONNECTOR_MIRROR,
        FulfillmentSignalType.SHIPPED, "SHIPPED", sharedRef, null, true, T0);

    TenantContext.setTenantId(tenantB);
    OrderJourney journeyB = newJourney(tenantB, "Q-XB", "APPROVED");
    saveSignal(tenantB, journeyB.getId(), FulfillmentSignalSource.CONNECTOR_MIRROR,
        FulfillmentSignalType.SHIPPED, "SHIPPED", sharedRef, null, true, T0);

    // Each tenant sees exactly its own single entry — the shared external key does not collide.
    assertThat(readService.operatorTimeline(journeyB.getId()).signalCount()).isEqualTo(1);
    TenantContext.setTenantId(tenantA);
    assertThat(readService.operatorTimeline(journeyA.getId()).signalCount()).isEqualTo(1);
  }

  @Test
  void duplicateFulfillmentSignalDoesNotDuplicateTimelineEntry() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newJourney(tenantId, "Q-D1", "APPROVED");

    // Use the real ingest path: a replayed signal (same source + type + sourceRef) collapses idempotently.
    RecordFulfillmentSignalRequest req = new RecordFulfillmentSignalRequest(
        "INTERNAL", "PACKED", "OK", new BigDecimal("0.990"), "wh-dup", null, true);
    service.recordSignal(journey.getId(), req, UUID.randomUUID());
    service.recordSignal(journey.getId(), req, UUID.randomUUID());

    OperatorFulfillmentTimelineResponse resp = readService.operatorTimeline(journey.getId());
    assertThat(resp.signalCount()).isEqualTo(1);
    assertThat(resp.timeline()).hasSize(1);
    assertThat(resp.timeline().get(0).type()).isEqualTo("PACKED");
  }

  @Test
  void returnRequestedSurfacesAsFlagAndEntryWithoutMutatingDeliveredMilestone() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newJourney(tenantId, "Q-R1", "APPROVED");

    // Verified internal delivery completes DELIVERED.
    service.recordSignal(journey.getId(), new RecordFulfillmentSignalRequest(
        "INTERNAL", "DELIVERED", "DELIVERED", null, "wh-deliver", null, true), UUID.randomUUID());
    OrderJourneyDetailDto beforeReturn = readService.detail(journey.getId());
    assertThat(milestone(beforeReturn, "DELIVERED").milestoneState()).isEqualTo("COMPLETED");

    // A RETURN_REQUESTED signal advances no canonical milestone and must not undo DELIVERED.
    service.recordSignal(journey.getId(), new RecordFulfillmentSignalRequest(
        "INTERNAL", "RETURN_REQUESTED", "REQUESTED", null, "ret-1", null, true), UUID.randomUUID());

    OperatorFulfillmentTimelineResponse resp = readService.operatorTimeline(journey.getId());
    assertThat(resp.returnRequested()).isTrue();
    assertThat(resp.timeline()).anySatisfy(e -> {
      assertThat(e.type()).isEqualTo("RETURN_REQUESTED");
      assertThat(e.label()).isEqualTo("Return requested");
    });

    OrderJourneyDetailDto afterReturn = readService.detail(journey.getId());
    assertThat(milestone(afterReturn, "DELIVERED").milestoneState()).isEqualTo("COMPLETED");
  }

  @Test
  void serializedResponseDoesNotLeakInternalSignalFields() throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newJourney(tenantId, "Q-L1", "APPROVED");

    String rawRef = "s3://internal-raw-bucket/carrier-payload-987";
    String externalKey = "idemp-key-xyz-secret";
    FulfillmentSignal saved = saveSignal(tenantId, journey.getId(), FulfillmentSignalSource.CONNECTOR_MIRROR,
        FulfillmentSignalType.SHIPPED, "SHIPPED", externalKey, rawRef, true, T0);

    OperatorFulfillmentTimelineResponse resp = readService.operatorTimeline(journey.getId());
    String json = JSON.writeValueAsString(resp);

    // Safe fields present.
    assertThat(json).contains("\"timeline\"").contains("SHIPPED").contains("MIRRORED");
    // Internal/sensitive material absent — by value and by field name.
    assertThat(json).doesNotContain(rawRef);
    assertThat(json).doesNotContain(externalKey);
    assertThat(json).doesNotContain(saved.getId().toString());
    assertThat(json).doesNotContain(tenantId.toString());
    assertThat(json)
        .doesNotContain("rawPayloadRef")
        .doesNotContain("rawPayload")
        .doesNotContain("idempotencyKey")
        .doesNotContain("sourceRef")
        .doesNotContain("auditEventId")
        .doesNotContain("connectorSecret")
        .doesNotContain("tenantId")
        .doesNotContain("confidence")
        .doesNotContain("storageRef")
        .doesNotContain("signalId");
  }

  private OrderJourneyMilestoneDto milestone(OrderJourneyDetailDto detail, String code) {
    return detail.milestones().stream().filter(m -> m.milestoneCode().equals(code)).findFirst().orElseThrow();
  }
}
