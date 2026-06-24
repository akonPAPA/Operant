package com.orderpilot.application.services.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.OrderJourneyDtos.CustomerSafeJourneyDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyDetailDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyMilestoneDto;
import com.orderpilot.api.dto.OrderJourneyDtos.RecordFulfillmentSignalRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.RecordManualMilestoneRequest;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.domain.reconciliation.ReconciliationCase;
import com.orderpilot.domain.reconciliation.ReconciliationCaseRepository;
import com.orderpilot.domain.reconciliation.ReconciliationSeverity;
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

/** OP-CAP-22 — derivation, tenant isolation, bounded reads, no-fake-payment, and audited signals. */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrderJourneyService.class, OrderJourneyReadService.class, OrderJourneyProjectionPublisher.class,
    AuditEventService.class, CoreConfiguration.class})
class OrderJourneyServiceTest {
  @Autowired private OrderJourneyService service;
  @Autowired private OrderJourneyReadService readService;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private ReconciliationCaseRepository reconciliationCaseRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void draftQuoteJourneyDerivesOrderedMilestonesAndKeepsPaymentUnknown() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-1", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();

    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);
    OrderJourneyDetailDto detail = readService.detail(journey.getId());

    // milestones returned sorted by canonical sort order
    assertThat(detail.milestones()).extracting(OrderJourneyMilestoneDto::sortOrder).isSorted();
    assertThat(milestone(detail, "REQUEST_RECEIVED").milestoneState()).isEqualTo("COMPLETED");
    assertThat(milestone(detail, "VALIDATION_COMPLETED").milestoneState()).isEqualTo("COMPLETED");
    assertThat(milestone(detail, "QUOTE_DRAFTED").milestoneState()).isEqualTo("COMPLETED");
    assertThat(milestone(detail, "QUOTE_APPROVED").milestoneState()).isEqualTo("COMPLETED");
    assertThat(detail.currentStage()).isEqualTo("QUOTE_APPROVED");
    // payment is never fabricated
    assertThat(milestone(detail, "PAYMENT_PENDING").milestoneState()).isEqualTo("UNKNOWN");
    assertThat(milestone(detail, "PAYMENT_CONFIRMED").milestoneState()).isEqualTo("UNKNOWN");
    assertThat(milestone(detail, "PAYMENT_CONFIRMED").evidenceLevel()).isEqualTo("UNKNOWN");
    assertThat(detail.paymentStatusAvailable()).isFalse();
    assertThat(detail.fulfillmentTrackingConnected()).isFalse();
  }

  @Test
  void journeyIsTenantIsolated() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantA, "Q-A", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "DRAFT", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> readService.detail(journey.getId())).isInstanceOf(NotFoundException.class);
    assertThat(readService.list(0).items()).isEmpty();
  }

  @Test
  void fulfillmentSignalAdvancesMilestoneIsAuditedAndCannotFakePayment() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-2", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    RecordFulfillmentSignalRequest req = new RecordFulfillmentSignalRequest(
        "INTERNAL", "PACKED", "OK", new BigDecimal("0.990"), "wh-ref-1", null, true);
    service.recordSignal(journey.getId(), req, UUID.randomUUID());

    OrderJourneyDetailDto detail = readService.detail(journey.getId());
    assertThat(milestone(detail, "PACKED").milestoneState()).isEqualTo("COMPLETED");
    assertThat(milestone(detail, "PACKED").evidenceLevel()).isEqualTo("VERIFIED");
    assertThat(detail.fulfillmentTrackingConnected()).isTrue();
    // payment milestones remain UNKNOWN — a fulfillment signal can never assert payment
    assertThat(milestone(detail, "PAYMENT_CONFIRMED").milestoneState()).isEqualTo("UNKNOWN");
    assertThat(detail.fulfillmentSignals()).hasSize(1);
    // audited
    long audited = auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream()
        .filter(e -> "ORDER_JOURNEY_SIGNAL_RECORDED".equals(e.getAction())).count();
    assertThat(audited).isEqualTo(1);
  }

  @Test
  void unverifiedCarrierDeliveredSignalDoesNotMarkDelivered() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-D1", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    // A carrier/WMS mirror is MIRRORED (unverified). It must NOT directly complete DELIVERED.
    RecordFulfillmentSignalRequest carrier = new RecordFulfillmentSignalRequest(
        "CONNECTOR_MIRROR", "DELIVERED", "DELIVERED", null, "carrier-1", null, true);
    service.recordSignal(journey.getId(), carrier, UUID.randomUUID());

    OrderJourneyDetailDto detail = readService.detail(journey.getId());
    assertThat(milestone(detail, "DELIVERED").milestoneState()).isEqualTo("ACTIVE");
    assertThat(milestone(detail, "DELIVERED").evidenceLevel()).isEqualTo("ESTIMATED");
    assertThat(detail.currentStage()).isNotEqualTo("DELIVERED");
    // customer-safe surface must not claim delivered on the strength of an unverified mirror
    CustomerSafeJourneyDto safe = readService.customerSafe(journey.getId());
    assertThat(safe.customerVisibleStatus()).isNotEqualTo("Delivered");
    assertThat(toJson(safe))
        .doesNotContain("carrier-1")
        .doesNotContain("\"sourceRef\"")
        .doesNotContain("\"sourceType\"")
        .doesNotContain("\"actorType\"");
  }

  @Test
  void verifiedOrManualDeliveredSignalConfirmsDelivery() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // Trusted internal verified delivery confirms DELIVERED.
    UUID internalQuote = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-D2", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney internalJourney = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, internalQuote);
    service.recordSignal(internalJourney.getId(), new RecordFulfillmentSignalRequest(
        "INTERNAL", "DELIVERED", "DELIVERED", null, "wh-deliver-1", null, true), UUID.randomUUID());
    OrderJourneyDetailDto internalDetail = readService.detail(internalJourney.getId());
    assertThat(milestone(internalDetail, "DELIVERED").milestoneState()).isEqualTo("COMPLETED");
    assertThat(milestone(internalDetail, "DELIVERED").evidenceLevel()).isEqualTo("VERIFIED");

    // Operator-attested MANUAL delivery also confirms DELIVERED.
    UUID manualQuote = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-D3", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney manualJourney = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, manualQuote);
    service.recordSignal(manualJourney.getId(), new RecordFulfillmentSignalRequest(
        "MANUAL", "DELIVERED", "DELIVERED", null, "op-confirm-1", null, true), UUID.randomUUID());
    OrderJourneyDetailDto manualDetail = readService.detail(manualJourney.getId());
    assertThat(milestone(manualDetail, "DELIVERED").milestoneState()).isEqualTo("COMPLETED");
    assertThat(milestone(manualDetail, "DELIVERED").evidenceLevel()).isEqualTo("MANUAL");
  }

  @Test
  void duplicateFulfillmentSignalIsIdempotent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-DUP", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    RecordFulfillmentSignalRequest req = new RecordFulfillmentSignalRequest(
        "INTERNAL", "PACKED", "OK", new BigDecimal("0.990"), "wh-ref-dup", null, true);
    service.recordSignal(journey.getId(), req, UUID.randomUUID());
    // Replay the same physical signal (same source + type + sourceRef) — must be a no-op.
    service.recordSignal(journey.getId(), req, UUID.randomUUID());

    OrderJourneyDetailDto detail = readService.detail(journey.getId());
    assertThat(detail.fulfillmentSignals()).hasSize(1);
    assertThat(milestone(detail, "PACKED").milestoneState()).isEqualTo("COMPLETED");
    long signalAudits = auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream()
        .filter(e -> "ORDER_JOURNEY_SIGNAL_RECORDED".equals(e.getAction())).count();
    assertThat(signalAudits).isEqualTo(1);
  }

  @Test
  void reconciliationCaseJourneyIsBlockedAndShowsInAttention() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID caseId = reconciliationCaseRepository.save(new ReconciliationCase(tenantId, UUID.randomUUID(), UUID.randomUUID(),
        new BigDecimal("100"), new BigDecimal("80"), new BigDecimal("-20"), ReconciliationSeverity.HIGH, "[\"short\"]", NOW)).getId();

    OrderJourney journey = service.refreshFromSource(JourneySourceType.RECONCILIATION_CASE, caseId);
    OrderJourneyDetailDto detail = readService.detail(journey.getId());

    assertThat(detail.blocked()).isTrue();
    assertThat(detail.currentStage()).isEqualTo("BLOCKED_EXCEPTION");
    assertThat(detail.riskLevel()).isEqualTo("HIGH");
    assertThat(readService.attention(0).items()).extracting(i -> i.id()).contains(journey.getId());
    assertThat(readService.attention(0).blockedCount()).isEqualTo(1);
  }

  @Test
  void customerSafeViewExcludesInternalOnlyMilestonesAndStatus() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-3", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    CustomerSafeJourneyDto safe = readService.customerSafe(journey.getId());

    assertThat(safe.customerVisibleStatus()).isNotBlank();
    // internal-only milestones must not leak into the customer-safe view
    assertThat(safe.milestones()).noneMatch(m -> m.milestoneCode().equals("VALIDATION_STARTED"));
    assertThat(safe.milestones()).noneMatch(m -> m.milestoneCode().equals("QUOTE_DRAFTED"));
    assertThat(safe.paymentStatusAvailable()).isFalse();
  }

  @Test
  void listIsBoundedByLimitCap() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    for (int i = 0; i < 3; i++) {
      UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-L" + i, UUID.randomUUID(), UUID.randomUUID(),
          UUID.randomUUID(), UUID.randomUUID(), "DRAFT", "USD", null, NOW)).getId();
      service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);
    }
    // request an oversized limit; service clamps to its max (50)
    assertThat(readService.list(9999).previewLimit()).isEqualTo(50);
    assertThat(readService.list(0).total()).isEqualTo(3);
  }

  // --- OP-CAP-46B manual milestone tests -------------------------------------------------------

  @Test
  void manualMilestoneAdvancesFulfillmentMilestoneAndIsAudited() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-M1", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    RecordManualMilestoneRequest req =
        new RecordManualMilestoneRequest("PACKED", "Warehouse double-checked SKUs", "Your order is packed", true);
    service.recordManualMilestone(journey.getId(), req, UUID.randomUUID());

    OrderJourneyDetailDto detail = readService.detail(journey.getId());
    assertThat(milestone(detail, "PACKED").milestoneState()).isEqualTo("COMPLETED");
    assertThat(milestone(detail, "PACKED").evidenceLevel()).isEqualTo("MANUAL");
    assertThat(detail.fulfillmentTrackingConnected()).isTrue();

    long audited = auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream()
        .filter(e -> "ORDER_JOURNEY_MANUAL_MILESTONE".equals(e.getAction())).count();
    assertThat(audited).isEqualTo(1);
  }

  @Test
  void manualDeliveredMilestoneConfirmsDeliveryThroughTrustedManualEvidence() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-MD", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    service.recordManualMilestone(journey.getId(),
        new RecordManualMilestoneRequest("DELIVERED", "Customer signed on doorstep", null, true), UUID.randomUUID());

    OrderJourneyDetailDto detail = readService.detail(journey.getId());
    // DELIVERED is high-stakes: only VERIFIED or operator-attested MANUAL evidence may complete it.
    assertThat(milestone(detail, "DELIVERED").milestoneState()).isEqualTo("COMPLETED");
    assertThat(milestone(detail, "DELIVERED").evidenceLevel()).isEqualTo("MANUAL");
    assertThat(detail.currentStage()).isEqualTo("DELIVERED");
  }

  @Test
  void manualMilestoneRejectsUnsupportedCode() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-MX", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    // A non-fulfillment milestone code maps to no signal type and is rejected (400 via GlobalExceptionHandler).
    assertThatThrownBy(() -> service.recordManualMilestone(journey.getId(),
        new RecordManualMilestoneRequest("QUOTE_APPROVED", "Not a fulfillment milestone", null, true),
        UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Manual milestone not supported");
  }

  @Test
  void manualMilestoneRejectsUnknownCode() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-MU", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    assertThatThrownBy(() -> service.recordManualMilestone(journey.getId(),
        new RecordManualMilestoneRequest("NOT_A_REAL_CODE", null, null, true), UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported milestone code");
  }

  @Test
  void manualMilestoneIsTenantIsolated() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantA, "Q-MT", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journeyA = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    // Tenant B cannot set a manual milestone on Tenant A's journey
    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> service.recordManualMilestone(journeyA.getId(),
        new RecordManualMilestoneRequest("PACKED", "Cross-tenant attempt", "leak", true),
        UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void manualMilestoneCustomerVisibleFalseHidesEventButStillDerivesMilestone() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-MN", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    // Operator sets milestone with customerVisible=false. The milestone itself uses
    // MilestoneCode.customerVisibleDefault() (PACKED=true), so the milestone IS visible in the
    // customer-safe view, but the operator's manual events are filtered out.
    service.recordManualMilestone(journey.getId(),
        new RecordManualMilestoneRequest("PACKED", "Internal packing note", "ignored when not visible", false),
        UUID.randomUUID());

    CustomerSafeJourneyDto safe = readService.customerSafe(journey.getId());
    assertThat(safe.milestones()).anyMatch(m -> m.milestoneCode().equals("PACKED")
        && "COMPLETED".equals(m.milestoneState()));
    assertThat(safe.events()).noneMatch(e -> "MANUAL_MILESTONE".equals(e.eventType()));

    OrderJourneyDetailDto detail = readService.detail(journey.getId());
    assertThat(milestone(detail, "PACKED").milestoneState()).isEqualTo("COMPLETED");
    assertThat(milestone(detail, "PACKED").evidenceLevel()).isEqualTo("MANUAL");
  }

  @Test
  void manualMilestoneInternalNoteNeverLeaksIntoCustomerSafeViewEvenWhenCustomerVisible() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-ML", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    String internalSecret = "INTERNAL-margin-23pct-supplier-Acme-do-not-share";
    String customerText = "Your order has been packed";
    service.recordManualMilestone(journey.getId(),
        new RecordManualMilestoneRequest("PACKED", internalSecret, customerText, true), UUID.randomUUID());

    // Customer-safe view: the internal note must NOT appear in any event/milestone field; the
    // customer-safe note MAY appear in the customer-visible milestone event.
    CustomerSafeJourneyDto safe = readService.customerSafe(journey.getId());
    assertThat(safe.events()).noneMatch(e -> e.message() != null && e.message().contains(internalSecret));
    assertThat(safe.events()).anyMatch(e -> e.message() != null && e.message().contains(customerText));
    assertThat(toJson(safe))
        .doesNotContain(internalSecret)
        .doesNotContain("\"sourceRef\"")
        .doesNotContain("\"sourceType\"")
        .doesNotContain("\"actorType\"")
        .doesNotContain("\"sortOrder\"")
        .doesNotContain("\"customerVisible\"")
        .doesNotContain("\"fulfillmentSignals\"")
        .doesNotContain("\"riskLevel\"")
        .doesNotContain("\"internalStatus\"");

    // Operator detail view DOES retain the internal note (in a strictly internal-only event), so the
    // operator does not lose the information — it is just never customer-visible.
    OrderJourneyDetailDto detail = readService.detail(journey.getId());
    assertThat(detail.recentEvents()).anyMatch(e -> e.message() != null && e.message().contains(internalSecret));
    assertThat(detail.recentEvents())
        .filteredOn(e -> e.message() != null && e.message().contains(internalSecret))
        .allMatch(e -> !e.customerVisible());
  }

  @Test
  void manualMilestoneSanitizesControlCharactersFromCustomerNote() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-MS", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    service.recordManualMilestone(journey.getId(),
        new RecordManualMilestoneRequest("PACKED", null, "line1\r\nline2\tinjected", true), UUID.randomUUID());

    CustomerSafeJourneyDto safe = readService.customerSafe(journey.getId());
    assertThat(safe.events())
        .filteredOn(e -> "MANUAL_MILESTONE".equals(e.eventType()))
        .allSatisfy(e -> {
          assertThat(e.message()).doesNotContain("\r").doesNotContain("\n").doesNotContain("\t");
          assertThat(e.message()).contains("line1").contains("line2");
        });
  }

  @Test
  void duplicateManualMilestoneDoesNotDuplicateBusinessEffect() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-MR", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    // Two manual PACKED actions. Each is a real, audited operator action, but the derived business
    // effect (the milestone projection) is recomputed idempotently — exactly one PACKED milestone row
    // in state COMPLETED, never duplicated.
    service.recordManualMilestone(journey.getId(),
        new RecordManualMilestoneRequest("PACKED", null, null, true), UUID.randomUUID());
    service.recordManualMilestone(journey.getId(),
        new RecordManualMilestoneRequest("PACKED", null, null, true), UUID.randomUUID());

    OrderJourneyDetailDto detail = readService.detail(journey.getId());
    assertThat(detail.milestones()).filteredOn(m -> "PACKED".equals(m.milestoneCode())).hasSize(1);
    assertThat(milestone(detail, "PACKED").milestoneState()).isEqualTo("COMPLETED");
  }

  @Test
  void customerSafeApiPathIsInternalProtectedPathNotPublicToken() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, "Q-MP", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    OrderJourney journey = service.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);

    CustomerSafeJourneyDto safe = readService.customerSafe(journey.getId());
    // Honest contract: the path is the internal protected API route for this journey id — not a
    // signed/opaque public secure link. It deliberately contains the journey id and no token.
    assertThat(safe.customerSafeApiPath())
        .isEqualTo("/api/v1/order-journeys/" + journey.getId() + "/customer-safe");
  }

  private OrderJourneyMilestoneDto milestone(OrderJourneyDetailDto detail, String code) {
    return detail.milestones().stream().filter(m -> m.milestoneCode().equals(code)).findFirst().orElseThrow();
  }

  private static String toJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception ex) {
      throw new AssertionError("Failed to serialize test DTO", ex);
    }
  }
}
