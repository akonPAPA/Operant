package com.orderpilot.integration.testdb;

import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static com.orderpilot.support.TestTenantFixtures.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.OrderJourneyDtos.CustomerSafeJourneyDto;
import com.orderpilot.api.dto.OrderJourneyDtos.PublicOrderTrackingView;
import com.orderpilot.api.dto.OrderJourneyDtos.RecordFulfillmentSignalRequest;
import com.orderpilot.application.services.journey.OrderJourneyReadService;
import com.orderpilot.application.services.journey.OrderJourneyService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.FulfillmentSignal;
import com.orderpilot.domain.journey.FulfillmentSignalRepository;
import com.orderpilot.domain.journey.FulfillmentSignalSource;
import com.orderpilot.domain.journey.FulfillmentSignalType;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.MilestoneState;
import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.domain.journey.OrderJourneyEventRepository;
import com.orderpilot.domain.journey.OrderJourneyMilestoneRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * OP-CAP-46J PostgreSQL proof for fulfillment-signal idempotency, tenant boundaries, and query
 * behavior. This extends the OP-CAP-46B V59 DB-level idempotency proof instead of duplicating it.
 *
 * <p>The fast H2 suite cannot prove this: H2 runs with {@code spring.flyway.enabled=false} and
 * {@code ddl-auto=create-drop}, so V53/V59 PostgreSQL schema/index behavior is not executed there.
 * This test runs under the real PostgreSQL integration-test datasource and exercises the same service
 * contract used by the backend journey API. It stays backend-only: no UI, no carrier calls, no order
 * state writes outside the existing journey projection contract.
 */
@RequiresPostgresIntegration
@Sql(scripts = {DatabaseIntegrationTestBase.CLEAN, DatabaseIntegrationTestBase.TENANTS,
    DatabaseIntegrationTestBase.USERS_ROLES})
class FulfillmentSignalIdempotencyPostgresIntegrationTest extends DatabaseIntegrationTestBase {

  private static final Instant BASE = Instant.parse("2026-06-16T12:00:00Z");
  private static final UUID OPERATOR_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID OPERATOR_B = UUID.fromString("44444444-4444-4444-8444-444444444444");

  @Autowired private FulfillmentSignalRepository signalRepository;
  @Autowired private OrderJourneyService journeyService;
  @Autowired private OrderJourneyReadService readService;
  @Autowired private OrderJourneyEventRepository eventRepository;
  @Autowired private OrderJourneyMilestoneRepository milestoneRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void migrationsApplyAndFulfillmentSignalSchemaExists() {
    List<String> applied = jdbcTemplate.queryForList(
        "SELECT version FROM flyway_schema_history WHERE success = true AND version IN ('53','59') "
            + "ORDER BY version", String.class);
    assertThat(applied).containsExactly("53", "59");

    assertThat(jdbcTemplate.queryForObject(
        "SELECT to_regclass('public.fulfillment_signal')::text", String.class))
        .isEqualTo("fulfillment_signal");

    List<String> columns = jdbcTemplate.queryForList(
        "SELECT column_name FROM information_schema.columns WHERE table_name = 'fulfillment_signal'",
        String.class);
    assertThat(columns).contains(
        "id", "tenant_id", "journey_id", "source_type", "signal_type", "signal_status",
        "confidence", "source_ref", "raw_payload_ref", "customer_visible", "received_at",
        "processed_at", "created_at");

    Integer queryIndex = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM pg_indexes WHERE tablename = 'fulfillment_signal' "
            + "AND indexname = 'idx_fulfillment_signal_journey_received'", Integer.class);
    assertThat(queryIndex).isEqualTo(1);

    Integer idempotencyIndex = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM pg_indexes WHERE tablename = 'fulfillment_signal' "
            + "AND indexname = 'ux_fulfillment_signal_idempotency'", Integer.class);
    assertThat(idempotencyIndex).isEqualTo(1);
  }

  @Test
  void duplicateSignalWithSameStableSourceRefIsRejectedByPartialUniqueIndex() {
    UUID journeyId = UUID.randomUUID();
    signalRepository.saveAndFlush(signal(journeyId, "wh-ref-1"));

    // Same tenant + journey + source + type + source_ref -> blocked by ux_fulfillment_signal_idempotency.
    assertThatThrownBy(() -> signalRepository.saveAndFlush(signal(journeyId, "wh-ref-1")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void signalsWithNullSourceRefAreNotConstrained() {
    UUID journeyId = UUID.randomUUID();
    // The partial index has WHERE source_ref IS NOT NULL, so two null-source_ref signals coexist.
    signalRepository.saveAndFlush(signal(journeyId, null));
    signalRepository.saveAndFlush(signal(journeyId, null));

    long packed = signalRepository.findByTenantIdAndJourneyIdOrderByReceivedAtAsc(TENANT_A, journeyId).stream()
        .filter(s -> s.getSignalType() == FulfillmentSignalType.PACKED)
        .count();
    assertThat(packed).isEqualTo(2);
  }

  @Test
  void duplicateFulfillmentSignalIsIdempotentOnPostgres() {
    OrderJourney journey = newApprovedQuoteJourney(TENANT_A, "Q-46J-IDEM");
    String stageBefore = journey.getCurrentStage().name();

    RecordFulfillmentSignalRequest request = new RecordFulfillmentSignalRequest(
        "INTERNAL", "RETURN_REQUESTED", "RETURN_REQUESTED", null, "return-ref-46j", null, true);

    TenantContext.setTenantId(TENANT_A);
    OrderJourney first = journeyService.recordSignal(journey.getId(), request, OPERATOR_A);
    OrderJourney replay = journeyService.recordSignal(journey.getId(), request, OPERATOR_A);

    assertThat(first.getId()).isEqualTo(journey.getId());
    assertThat(replay.getId()).isEqualTo(journey.getId());
    assertThat(replay.getCurrentStage().name()).isEqualTo(stageBefore);
    assertThat(signalsFor(TENANT_A, journey.getId())).hasSize(1);
    assertThat(fulfillmentSignalEventCount(TENANT_A, journey.getId())).isEqualTo(1);
    assertThat(milestoneRepository.findByTenantIdAndJourneyIdOrderBySortOrderAsc(TENANT_A, journey.getId()))
        .filteredOn(m -> m.getMilestoneState() == MilestoneState.COMPLETED)
        .noneMatch(m -> "SHIPPED".equals(m.getMilestoneCode().name())
            || "DELIVERED".equals(m.getMilestoneCode().name()));
  }

  @Test
  void sameExternalSignalKeyAcrossTenantsDoesNotCollide() {
    OrderJourney journeyA = newApprovedQuoteJourney(TENANT_A, "Q-46J-TA");
    OrderJourney journeyB = newApprovedQuoteJourney(TENANT_B, "Q-46J-TB");
    RecordFulfillmentSignalRequest request = new RecordFulfillmentSignalRequest(
        "INTERNAL", "RETURN_REQUESTED", "RETURN_REQUESTED", null, "shared-signal-46j", null, false);

    TenantContext.setTenantId(TENANT_A);
    journeyService.recordSignal(journeyA.getId(), request, OPERATOR_A);
    TenantContext.setTenantId(TENANT_B);
    journeyService.recordSignal(journeyB.getId(), request, OPERATOR_B);

    assertThat(signalsFor(TENANT_A, journeyA.getId())).hasSize(1);
    assertThat(signalsFor(TENANT_B, journeyB.getId())).hasSize(1);
    assertThat(signalsFor(TENANT_A, journeyB.getId())).isEmpty();
    assertThat(signalsFor(TENANT_B, journeyA.getId())).isEmpty();
  }

  @Test
  void fulfillmentSignalsListByJourneyIsTenantScopedAndOrdered() {
    UUID journeyA = UUID.randomUUID();
    UUID journeyB = UUID.randomUUID();
    signalRepository.save(signal(journeyA, "a-late", TENANT_A, FulfillmentSignalType.SHIPPED,
        BASE.plusSeconds(20)));
    signalRepository.save(signal(journeyA, "a-early", TENANT_A, FulfillmentSignalType.PACKED,
        BASE.plusSeconds(10)));
    signalRepository.save(signal(journeyB, "b-noise", TENANT_B, FulfillmentSignalType.DELIVERED,
        BASE.plusSeconds(15)));
    signalRepository.flush();

    assertThat(signalRepository.findByTenantIdAndJourneyIdOrderByReceivedAtAsc(TENANT_A, journeyA))
        .extracting(FulfillmentSignal::getSourceRef)
        .containsExactly("a-early", "a-late");
    assertThat(signalRepository.findByTenantIdAndJourneyIdOrderByReceivedAtDesc(
        TENANT_A, journeyA, PageRequest.of(0, 20)))
        .extracting(FulfillmentSignal::getSourceRef)
        .containsExactly("a-late", "a-early");
    assertThat(signalRepository.findByTenantIdAndJourneyIdOrderByReceivedAtAsc(TENANT_A, journeyB))
        .isEmpty();
  }

  @Test
  void invalidOrCrossTenantJourneySignalRejected() {
    OrderJourney journeyA = newApprovedQuoteJourney(TENANT_A, "Q-46J-XT");
    RecordFulfillmentSignalRequest request = new RecordFulfillmentSignalRequest(
        "INTERNAL", "RETURN_REQUESTED", "RETURN_REQUESTED", null, "cross-tenant-ref-46j", null, false);

    TenantContext.setTenantId(TENANT_B);
    assertThatThrownBy(() -> journeyService.recordSignal(journeyA.getId(), request, OPERATOR_B))
        .isInstanceOf(NotFoundException.class);

    assertThat(signalsFor(TENANT_A, journeyA.getId())).isEmpty();
    assertThat(signalsFor(TENANT_B, journeyA.getId())).isEmpty();
  }

  @Test
  void safeTrackingDtosDoNotExposeRawPayloadOrSignalInternals() throws Exception {
    OrderJourney journey = newApprovedQuoteJourney(TENANT_A, "Q-46J-DTO");
    RecordFulfillmentSignalRequest request = new RecordFulfillmentSignalRequest(
        "INTERNAL", "RETURN_REQUESTED", "RETURN_REQUESTED", null, "dto-source-ref-46j",
        "object-ref-raw-payload-46j", true);

    TenantContext.setTenantId(TENANT_A);
    journeyService.recordSignal(journey.getId(), request, OPERATOR_A);

    CustomerSafeJourneyDto customerSafe = readService.customerSafe(journey.getId());
    PublicOrderTrackingView publicTracking = readService.publicTracking(TENANT_A, journey.getId());
    assertThat(objectMapper.writeValueAsString(customerSafe))
        .doesNotContain("object-ref-raw-payload-46j", "dto-source-ref-46j", "fulfillmentSignals",
            "rawPayloadRef", "sourceRef");
    assertThat(objectMapper.writeValueAsString(publicTracking))
        .doesNotContain("object-ref-raw-payload-46j", "dto-source-ref-46j", "fulfillmentSignals",
            "rawPayloadRef", "sourceRef");
  }

  private FulfillmentSignal signal(UUID journeyId, String sourceRef) {
    return signal(journeyId, sourceRef, TENANT_A, FulfillmentSignalType.PACKED, BASE);
  }

  private FulfillmentSignal signal(UUID journeyId, String sourceRef, UUID tenantId,
      FulfillmentSignalType signalType, Instant receivedAt) {
    return new FulfillmentSignal(tenantId, journeyId, FulfillmentSignalSource.MANUAL,
        signalType, signalType.name(), null, sourceRef, null, false, receivedAt, receivedAt);
  }

  private OrderJourney newApprovedQuoteJourney(UUID tenantId, String quoteNumber) {
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(
        tenantId, quoteNumber, null, null, null, null, "APPROVED", "USD", null, BASE)).getId();
    return journeyService.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);
  }

  private List<FulfillmentSignal> signalsFor(UUID tenantId, UUID journeyId) {
    return signalRepository.findByTenantIdAndJourneyIdOrderByReceivedAtAsc(tenantId, journeyId);
  }

  private long fulfillmentSignalEventCount(UUID tenantId, UUID journeyId) {
    return eventRepository.findByTenantIdAndJourneyIdOrderByOccurredAtDesc(
            tenantId, journeyId, PageRequest.of(0, 20)).stream()
        .filter(e -> "FULFILLMENT_SIGNAL".equals(e.getEventType()))
        .count();
  }
}
