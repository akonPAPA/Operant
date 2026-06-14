package com.orderpilot.application.services.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.OrderJourneyDtos.CustomerSafeJourneyDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyDetailDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyMilestoneDto;
import com.orderpilot.api.dto.OrderJourneyDtos.RecordFulfillmentSignalRequest;
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
@Import({OrderJourneyService.class, OrderJourneyReadService.class, AuditEventService.class, CoreConfiguration.class})
class OrderJourneyServiceTest {
  @Autowired private OrderJourneyService service;
  @Autowired private OrderJourneyReadService readService;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private ReconciliationCaseRepository reconciliationCaseRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

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
    assertThat(safe.milestones()).allMatch(OrderJourneyMilestoneDto::customerVisible);
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

  private OrderJourneyMilestoneDto milestone(OrderJourneyDetailDto detail, String code) {
    return detail.milestones().stream().filter(m -> m.milestoneCode().equals(code)).findFirst().orElseThrow();
  }
}
