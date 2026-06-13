package com.orderpilot.application.services.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.PaymentObligationDtos.CustomerPaymentSummaryResponse;
import com.orderpilot.api.dto.PaymentObligationDtos.PaymentObligationResponse;
import com.orderpilot.application.services.payment.PaymentObligationService.AllocatePaymentCommand;
import com.orderpilot.application.services.payment.PaymentObligationService.CancelObligationCommand;
import com.orderpilot.application.services.payment.PaymentObligationService.CreateObligationCommand;
import com.orderpilot.application.services.payment.PaymentObligationService.MarkDisputedCommand;
import com.orderpilot.application.services.payment.PaymentObligationService.ResolveDisputeCommand;
import com.orderpilot.application.services.payment.PaymentObligationService.ReverseAllocationCommand;
import com.orderpilot.application.services.payment.PaymentObligationService.WriteOffObligationCommand;
import com.orderpilot.application.services.trust.CounterpartyTrustProfileService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.payment.PaymentAllocation;
import com.orderpilot.domain.payment.PaymentAllocationRepository;
import com.orderpilot.domain.payment.PaymentAllocationSourceType;
import com.orderpilot.domain.payment.PaymentAllocationStatus;
import com.orderpilot.domain.payment.PaymentObligation;
import com.orderpilot.domain.payment.PaymentObligationEventRepository;
import com.orderpilot.domain.payment.PaymentObligationRepository;
import com.orderpilot.domain.payment.PaymentObligationSourceType;
import com.orderpilot.domain.payment.PaymentObligationStatus;
import com.orderpilot.domain.trust.CounterpartyTrustProfile;
import com.orderpilot.domain.trust.CounterpartyTrustProfileRepository;
import com.orderpilot.domain.trust.CounterpartyTrustSignalRepository;
import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.domain.trust.TrustTier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 * Deterministic status/risk engine, BigDecimal money handling, overpayment rejection, append-only
 * events, tenant isolation, and OP-CAP-17B counterparty trust integration.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentObligationServiceStage17CTest {
  // Fixed "today" = 2026-06-14 so overdue/risk computations are fully deterministic.
  private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-14T12:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate FUTURE_DUE = LocalDate.of(2026, 12, 31);
  private static final LocalDate OVERDUE_4_DAYS = LocalDate.of(2026, 6, 10);   // 4 days late -> MEDIUM
  private static final LocalDate OVERDUE_13_DAYS = LocalDate.of(2026, 6, 1);   // 13 days late -> HIGH
  private static final Instant PAID_AT = Instant.parse("2026-06-14T10:00:00Z");

  @Autowired private PaymentObligationService service;
  @Autowired private PaymentObligationRepository obligations;
  @Autowired private PaymentAllocationRepository allocations;
  @Autowired private PaymentObligationEventRepository events;
  @Autowired private CounterpartyTrustProfileRepository profiles;
  @Autowired private CounterpartyTrustSignalRepository signals;
  @Autowired private CounterpartyTrustProfileService trustProfileService;

  @BeforeEach
  void fixClock() {
    ReflectionTestUtils.setField(service, "clock", FIXED_CLOCK);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private CreateObligationCommand createCmd(UUID tenantId, UUID cp, BigDecimal total, String currency, LocalDate due) {
    return new CreateObligationCommand(tenantId, cp, PaymentObligationSourceType.MANUAL, null,
        "EXT-REF", "INV-001", total, currency, due, null);
  }

  private AllocatePaymentCommand allocateCmd(UUID tenantId, UUID obligationId, BigDecimal amount, String currency) {
    return new AllocatePaymentCommand(tenantId, obligationId, PaymentAllocationSourceType.MANUAL, null,
        amount, currency, PAID_AT, "manual");
  }

  private List<String> signalCodes(UUID tenantId, UUID cp) {
    return signals.findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(tenantId, cp, PageRequest.of(0, 25))
        .stream().map(s -> s.getSignalCode().name()).toList();
  }

  // ----------------------------- deterministic status / risk -----------------------------

  @Test
  void createValidObligationIsOpenLowRisk() {
    UUID tenantId = UUID.randomUUID();
    PaymentObligation o = service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("100.00"), "usd", FUTURE_DUE));

    assertThat(o.getStatus()).isEqualTo(PaymentObligationStatus.OPEN);
    assertThat(o.getRiskLevel()).isEqualTo(TrustRiskLevel.LOW);
    assertThat(o.getCurrency()).isEqualTo("USD");
    assertThat(o.getAmountTotal()).isEqualByComparingTo("100.0000");
    assertThat(o.getAmountPaid()).isEqualByComparingTo("0");
    assertThat(o.getAmountRemaining()).isEqualByComparingTo("100.0000");
    assertThat(events.findByTenantIdAndPaymentObligationIdOrderByCreatedAtDesc(tenantId, o.getId(), PageRequest.of(0, 25)))
        .extracting(e -> e.getEventType().name()).containsExactly("OBLIGATION_CREATED");
  }

  @Test
  void createPastDueZeroPaidIsOverdue() {
    UUID tenantId = UUID.randomUUID();
    PaymentObligation medium = service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("50.00"), "USD", OVERDUE_4_DAYS));
    PaymentObligation high = service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("50.00"), "USD", OVERDUE_13_DAYS));

    assertThat(medium.getStatus()).isEqualTo(PaymentObligationStatus.OVERDUE);
    assertThat(medium.getRiskLevel()).isEqualTo(TrustRiskLevel.MEDIUM);
    assertThat(high.getStatus()).isEqualTo(PaymentObligationStatus.OVERDUE);
    assertThat(high.getRiskLevel()).isEqualTo(TrustRiskLevel.HIGH);
  }

  @Test
  void allocatePartialPaymentUpdatesAmountsAndStatus() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    PaymentObligation o = service.createObligation(createCmd(tenantId, cp, new BigDecimal("100.00"), "USD", FUTURE_DUE));

    PaymentObligation after = service.allocatePayment(allocateCmd(tenantId, o.getId(), new BigDecimal("40.00"), "USD"));

    assertThat(after.getStatus()).isEqualTo(PaymentObligationStatus.PARTIALLY_PAID);
    assertThat(after.getRiskLevel()).isEqualTo(TrustRiskLevel.MEDIUM);
    assertThat(after.getAmountPaid()).isEqualByComparingTo("40.0000");
    assertThat(after.getAmountRemaining()).isEqualByComparingTo("60.0000");
    assertThat(signalCodes(tenantId, cp)).contains("PARTIAL_PAYMENT_OPEN");
  }

  @Test
  void allocateFullPaymentMarksPaidLowRisk() {
    UUID tenantId = UUID.randomUUID();
    PaymentObligation o = service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("100.00"), "USD", FUTURE_DUE));

    PaymentObligation after = service.allocatePayment(allocateCmd(tenantId, o.getId(), new BigDecimal("100.00"), "USD"));

    assertThat(after.getStatus()).isEqualTo(PaymentObligationStatus.PAID);
    assertThat(after.getRiskLevel()).isEqualTo(TrustRiskLevel.LOW);
    assertThat(after.getAmountRemaining()).isEqualByComparingTo("0");
  }

  @Test
  void overpaymentIsRejected() {
    UUID tenantId = UUID.randomUUID();
    PaymentObligation o = service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("100.00"), "USD", FUTURE_DUE));

    assertThatThrownBy(() -> service.allocatePayment(allocateCmd(tenantId, o.getId(), new BigDecimal("150.00"), "USD")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overpayment");
    assertThat(obligations.findByIdAndTenantId(o.getId(), tenantId).orElseThrow().getAmountPaid()).isEqualByComparingTo("0");
  }

  @Test
  void currencyMismatchAllocationIsRejected() {
    UUID tenantId = UUID.randomUUID();
    PaymentObligation o = service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("100.00"), "USD", FUTURE_DUE));

    assertThatThrownBy(() -> service.allocatePayment(allocateCmd(tenantId, o.getId(), new BigDecimal("10.00"), "EUR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("currency");
  }

  @Test
  void reversingAllocationRestoresAmountsAndStatus() {
    UUID tenantId = UUID.randomUUID();
    PaymentObligation o = service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("100.00"), "USD", FUTURE_DUE));
    service.allocatePayment(allocateCmd(tenantId, o.getId(), new BigDecimal("100.00"), "USD"));
    PaymentAllocation allocation = allocations
        .findByTenantIdAndPaymentObligationIdOrderByAllocatedAtDesc(tenantId, o.getId(), PageRequest.of(0, 25)).get(0);

    PaymentObligation after = service.reverseAllocation(new ReverseAllocationCommand(tenantId, allocation.getId(), "operator reversal"));

    assertThat(after.getStatus()).isEqualTo(PaymentObligationStatus.OPEN);
    assertThat(after.getAmountPaid()).isEqualByComparingTo("0");
    assertThat(after.getAmountRemaining()).isEqualByComparingTo("100.0000");
    assertThat(allocations.findByIdAndTenantId(allocation.getId(), tenantId).orElseThrow().getAllocationStatus())
        .isEqualTo(PaymentAllocationStatus.REVERSED);
  }

  @Test
  void disputedStatusIsPreservedUntilResolved() {
    UUID tenantId = UUID.randomUUID();
    PaymentObligation o = service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("100.00"), "USD", FUTURE_DUE));

    service.markDisputed(new MarkDisputedCommand(tenantId, o.getId(), "customer dispute"));
    PaymentObligation afterAllocate = service.allocatePayment(allocateCmd(tenantId, o.getId(), new BigDecimal("40.00"), "USD"));
    assertThat(afterAllocate.getStatus()).isEqualTo(PaymentObligationStatus.DISPUTED);
    assertThat(afterAllocate.getAmountPaid()).isEqualByComparingTo("40.0000");

    PaymentObligation resolved = service.resolveDispute(new ResolveDisputeCommand(tenantId, o.getId(), "resolved"));
    assertThat(resolved.getStatus()).isEqualTo(PaymentObligationStatus.PARTIALLY_PAID);
  }

  @Test
  void cancelledAndWrittenOffAreNotAutoMutatedByRecalc() {
    UUID tenantId = UUID.randomUUID();
    PaymentObligation cancel = service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("100.00"), "USD", OVERDUE_13_DAYS));
    PaymentObligation writeOff = service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("100.00"), "USD", OVERDUE_13_DAYS));

    service.cancelObligation(new CancelObligationCommand(tenantId, cancel.getId(), "cancelled"));
    service.writeOffObligation(new WriteOffObligationCommand(tenantId, writeOff.getId(), "bad debt"));

    assertThat(service.recalculateStatus(tenantId, cancel.getId()).getStatus()).isEqualTo(PaymentObligationStatus.CANCELLED);
    assertThat(service.recalculateStatus(tenantId, writeOff.getId()).getStatus()).isEqualTo(PaymentObligationStatus.WRITTEN_OFF);
  }

  @Test
  void eventsAreAppendedForAllStateChanges() {
    UUID tenantId = UUID.randomUUID();
    PaymentObligation o = service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("100.00"), "USD", FUTURE_DUE));
    service.allocatePayment(allocateCmd(tenantId, o.getId(), new BigDecimal("40.00"), "USD"));
    service.markDisputed(new MarkDisputedCommand(tenantId, o.getId(), "dispute"));

    assertThat(events.findByTenantIdAndPaymentObligationIdOrderByCreatedAtDesc(tenantId, o.getId(), PageRequest.of(0, 25)))
        .extracting(e -> e.getEventType().name())
        .containsExactlyInAnyOrder("OBLIGATION_CREATED", "PAYMENT_ALLOCATED", "MARKED_DISPUTED");
  }

  @Test
  void bigDecimalScaleIsPreserved() {
    UUID tenantId = UUID.randomUUID();
    PaymentObligation o = service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("99.99"), "USD", FUTURE_DUE));

    assertThat(o.getAmountTotal().scale()).isEqualTo(4);
    assertThat(o.getAmountTotal()).isEqualByComparingTo("99.9900");
    assertThat(o.getAmountRemaining()).isEqualByComparingTo("99.9900");
  }

  @Test
  void invalidCurrencyIsRejected() {
    UUID tenantId = UUID.randomUUID();
    assertThatThrownBy(() -> service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("10.00"), "US", FUTURE_DUE)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
  }

  @Test
  void negativeAmountIsRejected() {
    UUID tenantId = UUID.randomUUID();
    assertThatThrownBy(() -> service.createObligation(createCmd(tenantId, UUID.randomUUID(), new BigDecimal("-1.00"), "USD", FUTURE_DUE)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amountTotal");
  }

  @Test
  void createWithSameSourceRefIsIdempotent() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    UUID ref = UUID.randomUUID();
    CreateObligationCommand cmd = new CreateObligationCommand(tenantId, cp, PaymentObligationSourceType.INVOICE_MIRROR,
        ref, "EXT", "INV", new BigDecimal("10.00"), "USD", FUTURE_DUE, null);

    PaymentObligation first = service.createObligation(cmd);
    PaymentObligation second = service.createObligation(cmd);

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(obligations.findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(tenantId, cp, PageRequest.of(0, 25))).hasSize(1);
  }

  // ----------------------------- OP-CAP-17B trust integration -----------------------------

  @Test
  void overdueObligationIncrementsOverduePaymentCountAndSignals() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    service.createObligation(createCmd(tenantId, cp, new BigDecimal("100.00"), "USD", OVERDUE_13_DAYS));

    CounterpartyTrustProfile profile = profiles.findByTenantIdAndCustomerAccountId(tenantId, cp).orElseThrow();
    assertThat(profile.getOverduePaymentCount()).isEqualTo(1);
    // Overdue behaviour lowers payment reliability below the clean-payment value (70).
    assertThat(profile.getPaymentReliabilityScore()).isEqualTo(60);
    assertThat(signalCodes(tenantId, cp)).contains("PAYMENT_OVERDUE");
  }

  @Test
  void paymentAllocationUpdatesLastPaymentAtAndReliability() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    PaymentObligation o = service.createObligation(createCmd(tenantId, cp, new BigDecimal("100.00"), "USD", FUTURE_DUE));
    service.allocatePayment(allocateCmd(tenantId, o.getId(), new BigDecimal("100.00"), "USD"));

    CounterpartyTrustProfile profile = profiles.findByTenantIdAndCustomerAccountId(tenantId, cp).orElseThrow();
    assertThat(profile.getLastPaymentAt()).isEqualTo(PAID_AT);
    // Paid behaviour, no overdue/dispute -> reliability rises above the neutral 50 baseline.
    assertThat(profile.getPaymentReliabilityScore()).isEqualTo(70);
  }

  @Test
  void disputeCreatesDisputeSignalAndIncrementsDisputedCount() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    PaymentObligation o = service.createObligation(createCmd(tenantId, cp, new BigDecimal("100.00"), "USD", FUTURE_DUE));
    service.markDisputed(new MarkDisputedCommand(tenantId, o.getId(), "dispute"));

    CounterpartyTrustProfile profile = profiles.findByTenantIdAndCustomerAccountId(tenantId, cp).orElseThrow();
    assertThat(profile.getDisputedCount()).isEqualTo(1);
    assertThat(signalCodes(tenantId, cp)).contains("DISPUTE_HISTORY_HIGH");
  }

  @Test
  void tenantAObligationDoesNotUpdateTenantBProfile() {
    UUID cp = UUID.randomUUID();
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    service.createObligation(createCmd(tenantA, cp, new BigDecimal("100.00"), "USD", OVERDUE_13_DAYS));

    assertThat(profiles.findByTenantIdAndCustomerAccountId(tenantA, cp)).isPresent();
    assertThat(profiles.findByTenantIdAndCustomerAccountId(tenantB, cp)).isEmpty();
  }

  @Test
  void highPaymentSignalFloorsTierDespitePositiveHistory() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    // Strong positive order history first -> TRUSTED/STABLE.
    CounterpartyTrustProfile seeded = trustProfileService.getOrCreateProfile(tenantId, cp);
    CounterpartyTrustProfile managed = profiles.findByTenantIdAndCustomerAccountId(tenantId, cp).orElseThrow();
    for (int i = 0; i < 20; i++) {
      managed.recordCompletedOrder(Instant.parse("2026-06-13T00:00:00Z"));
    }
    profiles.save(managed);
    trustProfileService.recomputeProfile(tenantId, cp);

    // An overdue payment must floor the tier to at least WATCHLIST regardless of history.
    service.createObligation(createCmd(tenantId, cp, new BigDecimal("500.00"), "USD", OVERDUE_13_DAYS));

    CounterpartyTrustProfile after = profiles.findByTenantIdAndCustomerAccountId(tenantId, cp).orElseThrow();
    assertThat(after.getLastRiskLevel()).isEqualTo(TrustRiskLevel.HIGH);
    assertThat(after.getTrustTier()).isIn(TrustTier.WATCHLIST, TrustTier.HIGH_RISK);
    assertThat(seeded.getId()).isEqualTo(after.getId());
  }

  // ----------------------------- read side / tenant scoping -----------------------------

  @Test
  void listCustomerObligationsIsTenantScopedAndBounded() {
    UUID cp = UUID.randomUUID();
    UUID tenantA = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      service.createObligation(createCmd(tenantA, cp, new BigDecimal("10.00"), "USD", FUTURE_DUE));
    }

    TenantContext.setTenantId(tenantA);
    assertThat(service.listCustomerObligations(cp, null, 2)).hasSize(2); // bounded by limit

    TenantContext.setTenantId(UUID.randomUUID());
    assertThat(service.listCustomerObligations(cp, null, 25)).isEmpty(); // other tenant sees nothing
  }

  @Test
  void getObligationViewIsTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    PaymentObligation o = service.createObligation(createCmd(tenantA, UUID.randomUUID(), new BigDecimal("10.00"), "USD", FUTURE_DUE));

    TenantContext.setTenantId(tenantA);
    PaymentObligationResponse view = service.getObligationView(o.getId());
    assertThat(view.id()).isEqualTo(o.getId());
    assertThat(view.status()).isEqualTo("OPEN");
    assertThat(view.recentEvents()).extracting(e -> e.eventType()).containsExactly("OBLIGATION_CREATED");

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> service.getObligationView(o.getId()))
        .isInstanceOf(com.orderpilot.common.errors.NotFoundException.class);
  }

  @Test
  void paymentSummaryAggregatesAndIsTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    PaymentObligation open = service.createObligation(createCmd(tenantA, cp, new BigDecimal("100.00"), "USD", FUTURE_DUE));
    service.createObligation(createCmd(tenantA, cp, new BigDecimal("80.00"), "USD", OVERDUE_13_DAYS));
    PaymentObligation paid = service.createObligation(createCmd(tenantA, cp, new BigDecimal("50.00"), "USD", FUTURE_DUE));
    service.allocatePayment(allocateCmd(tenantA, paid.getId(), new BigDecimal("50.00"), "USD"));
    assertThat(open.getId()).isNotNull();

    TenantContext.setTenantId(tenantA);
    CustomerPaymentSummaryResponse summary = service.getCustomerPaymentSummary(cp);

    assertThat(summary.currency()).isEqualTo("USD");
    assertThat(summary.openCount()).isEqualTo(1);
    assertThat(summary.overdueCount()).isEqualTo(1);
    assertThat(summary.paidCount()).isEqualTo(1);
    assertThat(summary.totalOpenAmount()).isEqualByComparingTo("100.0000");
    assertThat(summary.totalOverdueAmount()).isEqualByComparingTo("80.0000");
    assertThat(summary.totalPaidAmount()).isEqualByComparingTo("50.0000");
    assertThat(summary.paymentReliabilityScore()).isNotNull();

    TenantContext.setTenantId(UUID.randomUUID());
    CustomerPaymentSummaryResponse empty = service.getCustomerPaymentSummary(cp);
    assertThat(empty.openCount()).isZero();
    assertThat(empty.overdueCount()).isZero();
  }

  @Test
  void paymentSummaryWithheldForMixedCurrency() {
    UUID tenantA = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    service.createObligation(createCmd(tenantA, cp, new BigDecimal("100.00"), "USD", FUTURE_DUE));
    service.createObligation(createCmd(tenantA, cp, new BigDecimal("100.00"), "EUR", FUTURE_DUE));

    TenantContext.setTenantId(tenantA);
    CustomerPaymentSummaryResponse summary = service.getCustomerPaymentSummary(cp);

    assertThat(summary.currency()).isEqualTo("MIXED");
    assertThat(summary.totalOpenAmount()).isNull();
    assertThat(summary.openCount()).isEqualTo(2);
  }

  @Test
  void limitIsClampedToSafeBounds() {
    assertThat(PaymentObligationService.clampLimit(0)).isEqualTo(25);
    assertThat(PaymentObligationService.clampLimit(-5)).isEqualTo(25);
    assertThat(PaymentObligationService.clampLimit(10)).isEqualTo(10);
    assertThat(PaymentObligationService.clampLimit(9999)).isEqualTo(100);
  }
}
