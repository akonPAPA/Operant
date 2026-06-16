package com.orderpilot.application.services.payment;

import com.orderpilot.api.dto.PaymentObligationDtos.CustomerPaymentSummaryResponse;
import com.orderpilot.api.dto.PaymentObligationDtos.PaymentObligationEventResponse;
import com.orderpilot.api.dto.PaymentObligationDtos.PaymentObligationResponse;
import com.orderpilot.api.dto.TrustDtos.CounterpartyTrustSignalView;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.trust.CounterpartyTrustProfileService;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.payment.PaymentAllocation;
import com.orderpilot.domain.payment.PaymentAllocationRepository;
import com.orderpilot.domain.payment.PaymentAllocationSourceType;
import com.orderpilot.domain.payment.PaymentObligation;
import com.orderpilot.domain.payment.PaymentObligationEvent;
import com.orderpilot.domain.payment.PaymentObligationEventRepository;
import com.orderpilot.domain.payment.PaymentObligationEventType;
import com.orderpilot.domain.payment.PaymentObligationRepository;
import com.orderpilot.domain.payment.PaymentObligationSourceType;
import com.orderpilot.domain.payment.PaymentObligationStatus;
import com.orderpilot.domain.payment.PaymentObligationStatusAggregate;
import com.orderpilot.domain.trust.CounterpartySignalCode;
import com.orderpilot.domain.trust.CounterpartyTrustProfile;
import com.orderpilot.domain.trust.CounterpartyTrustProfileRepository;
import com.orderpilot.domain.trust.TrustRiskLevel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * Deterministic, tenant-scoped command/query service for internal payment obligations. It is NOT a
 * payment processor, PSP, or bank integration: it never calls external systems, never stores raw
 * bank/PSP payloads or card/NFC data, and never lets AI/bot/frontend/connector/webhook mutate trusted
 * state. All status/risk decisions are computed deterministically from {@link BigDecimal} amounts and a
 * deterministic {@link Clock}; AI is never consulted. Material payment behaviour (overdue, disputed,
 * partial, payment received) is pushed into the OP-CAP-17B counterparty trust profile in the same
 * transaction, so an obligation update and its trust signal commit or roll back together.
 */
@Service
public class PaymentObligationService {
  public static final int DEFAULT_LIMIT = 25;
  static final int MAX_LIMIT = 100;
  static final int MONEY_SCALE = 4;
  /** Overdue 1..7 days = MEDIUM; beyond that = HIGH (conservative; no finance policy invented here). */
  static final long OVERDUE_MEDIUM_MAX_DAYS = 7;
  /** Bounded scan cap for overdue detection — never an unbounded history scan per request. */
  static final int OVERDUE_SCAN_LIMIT = 200;
  static final int RECENT_SIGNAL_LIMIT = 10;
  private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");
  private static final List<PaymentObligationStatus> OVERDUE_ELIGIBLE =
      List.of(PaymentObligationStatus.OPEN, PaymentObligationStatus.PARTIALLY_PAID);

  private final PaymentObligationRepository obligations;
  private final PaymentAllocationRepository allocations;
  private final PaymentObligationEventRepository events;
  private final CounterpartyTrustProfileService trustProfileService;
  private final CounterpartyTrustProfileRepository trustProfiles;
  private final AuditEventRepository auditEvents;
  private final JsonSupport jsonSupport;
  private final Clock clock;

  public PaymentObligationService(
      PaymentObligationRepository obligations,
      PaymentAllocationRepository allocations,
      PaymentObligationEventRepository events,
      CounterpartyTrustProfileService trustProfileService,
      CounterpartyTrustProfileRepository trustProfiles,
      AuditEventRepository auditEvents,
      JsonSupport jsonSupport,
      Clock clock) {
    this.obligations = obligations;
    this.allocations = allocations;
    this.events = events;
    this.trustProfileService = trustProfileService;
    this.trustProfiles = trustProfiles;
    this.auditEvents = auditEvents;
    this.jsonSupport = jsonSupport;
    this.clock = clock;
  }

  // ----------------------------- command records -----------------------------

  public record CreateObligationCommand(
      UUID tenantId, UUID customerAccountId, PaymentObligationSourceType sourceType, UUID sourceRefId,
      String externalReference, String obligationNumber, BigDecimal amountTotal, String currency,
      LocalDate dueDate, Instant issuedAt) {}

  public record AllocatePaymentCommand(
      UUID tenantId, UUID obligationId, PaymentAllocationSourceType sourceType, UUID sourceRefId,
      BigDecimal allocatedAmount, String currency, Instant paymentAt, String reasonCode) {}

  public record ReverseAllocationCommand(UUID tenantId, UUID allocationId, String reasonCode) {}

  public record MarkDisputedCommand(UUID tenantId, UUID obligationId, String reasonSummary) {}

  public record ResolveDisputeCommand(UUID tenantId, UUID obligationId, String reasonSummary) {}

  public record CancelObligationCommand(UUID tenantId, UUID obligationId, String reasonSummary) {}

  public record WriteOffObligationCommand(UUID tenantId, UUID obligationId, String reasonSummary) {}

  // ----------------------------- commands -----------------------------

  @Transactional
  public PaymentObligation createObligation(CreateObligationCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    UUID customerAccountId = required(cmd.customerAccountId(), "customerAccountId");
    PaymentObligationSourceType sourceType = required(cmd.sourceType(), "sourceType");
    BigDecimal amountTotal = normalizeAmount(cmd.amountTotal(), "amountTotal", false);
    String currency = normalizeCurrency(cmd.currency());
    String externalReference = bound(cmd.externalReference(), 120);
    String obligationNumber = bound(cmd.obligationNumber(), 80);

    // Idempotency: at most one obligation per (tenant, sourceType, sourceRefId) when a ref is present.
    if (cmd.sourceRefId() != null) {
      Optional<PaymentObligation> existing =
          obligations.findByTenantIdAndSourceTypeAndSourceRefId(tenantId, sourceType, cmd.sourceRefId());
      if (existing.isPresent()) {
        return existing.get();
      }
    }

    Instant now = clock.instant();
    LocalDate today = LocalDate.now(clock);
    PaymentObligationStatus status = computeActiveStatus(amountTotal, zeroMoney(), cmd.dueDate(), today);
    TrustRiskLevel risk = computeRisk(status, cmd.dueDate(), today);

    PaymentObligation obligation = new PaymentObligation(tenantId, customerAccountId, sourceType,
        cmd.sourceRefId(), externalReference, obligationNumber, amountTotal, currency, cmd.dueDate(),
        cmd.issuedAt(), status, risk, now);
    obligations.save(obligation);

    appendEvent(obligation, PaymentObligationEventType.OBLIGATION_CREATED, null, null, null,
        "Obligation created", now);
    recordObligationAudit(tenantId, obligation, "PAYMENT_OBLIGATION_CREATED");

    // Created already overdue (past due, nothing paid) counts as an overdue detection.
    if (status == PaymentObligationStatus.OVERDUE) {
      trustProfileService.applyPaymentObligationSignal(tenantId, customerAccountId,
          CounterpartySignalCode.PAYMENT_OVERDUE, risk, 7, obligation.getId(),
          "Payment obligation created already overdue.", true, false);
    }
    return obligation;
  }

  @Transactional
  public PaymentObligation allocatePayment(AllocatePaymentCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    PaymentObligation obligation = load(tenantId, cmd.obligationId());
    if (obligation.getStatus().isClosedTerminal()) {
      throw new IllegalArgumentException("Cannot allocate payment to a closed (cancelled/written-off) obligation");
    }
    PaymentAllocationSourceType sourceType = required(cmd.sourceType(), "sourceType");
    BigDecimal amount = normalizeAmount(cmd.allocatedAmount(), "allocatedAmount", true);
    String currency = normalizeCurrency(cmd.currency());
    if (!currency.equals(obligation.getCurrency())) {
      throw new IllegalArgumentException("Allocation currency does not match obligation currency");
    }
    if (obligation.getAmountPaid().add(amount).compareTo(obligation.getAmountTotal()) > 0) {
      // Overpayment is never silently accepted in this foundation — reject it.
      throw new IllegalArgumentException("Allocation would exceed the obligation total (overpayment rejected)");
    }

    Instant now = clock.instant();
    Instant paymentAt = cmd.paymentAt() != null ? cmd.paymentAt() : now;
    LocalDate today = LocalDate.now(clock);

    PaymentObligationStatus prevStatus = obligation.getStatus();
    BigDecimal prevPaid = obligation.getAmountPaid();
    BigDecimal prevRemaining = obligation.getAmountRemaining();

    obligation.addPayment(amount, paymentAt, now);
    allocations.save(new PaymentAllocation(tenantId, obligation.getId(), obligation.getCustomerAccountId(),
        sourceType, cmd.sourceRefId(), amount, currency, paymentAt, bound(cmd.reasonCode(), 80), now));

    // Disputed obligations preserve DISPUTED status while amounts still update.
    if (prevStatus != PaymentObligationStatus.DISPUTED) {
      PaymentObligationStatus newStatus = computeActiveStatus(
          obligation.getAmountTotal(), obligation.getAmountPaid(), obligation.getDueDate(), today);
      obligation.applyStatusAndRisk(newStatus, computeRisk(newStatus, obligation.getDueDate(), today), now);
    }

    appendEvent(obligation, PaymentObligationEventType.PAYMENT_ALLOCATED, prevStatus, prevPaid, prevRemaining,
        "Payment allocated", now);
    recordObligationAudit(tenantId, obligation, "PAYMENT_OBLIGATION_PAYMENT_ALLOCATED");

    // Receiving a payment always refreshes payment-reliability on the counterparty trust profile.
    trustProfileService.recordPaymentReliabilityUpdate(tenantId, obligation.getCustomerAccountId(), paymentAt);
    notifyTrustOnTransition(tenantId, prevStatus, obligation, now);
    return obligation;
  }

  @Transactional
  public PaymentObligation reverseAllocation(ReverseAllocationCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    PaymentAllocation allocation = allocations
        .findByIdAndTenantId(required(cmd.allocationId(), "allocationId"), tenantId)
        .orElseThrow(() -> new NotFoundException("Payment allocation not found"));
    if (!allocation.isApplied()) {
      throw new ConflictException("Payment allocation is already reversed");
    }
    PaymentObligation obligation = load(tenantId, allocation.getPaymentObligationId());

    Instant now = clock.instant();
    LocalDate today = LocalDate.now(clock);
    PaymentObligationStatus prevStatus = obligation.getStatus();
    BigDecimal prevPaid = obligation.getAmountPaid();
    BigDecimal prevRemaining = obligation.getAmountRemaining();

    allocation.reverse(bound(cmd.reasonCode(), 80), now);
    obligation.removePayment(allocation.getAllocatedAmount(), now);

    // Preserve terminal/disputed states; otherwise recompute deterministically.
    if (!obligation.getStatus().isClosedTerminal() && prevStatus != PaymentObligationStatus.DISPUTED) {
      PaymentObligationStatus newStatus = computeActiveStatus(
          obligation.getAmountTotal(), obligation.getAmountPaid(), obligation.getDueDate(), today);
      obligation.applyStatusAndRisk(newStatus, computeRisk(newStatus, obligation.getDueDate(), today), now);
    }

    appendEvent(obligation, PaymentObligationEventType.PAYMENT_REVERSED, prevStatus, prevPaid, prevRemaining,
        "Payment reversed", now);
    recordObligationAudit(tenantId, obligation, "PAYMENT_OBLIGATION_PAYMENT_REVERSED");
    notifyTrustOnTransition(tenantId, prevStatus, obligation, now);
    return obligation;
  }

  @Transactional
  public PaymentObligation markDisputed(MarkDisputedCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    PaymentObligation obligation = load(tenantId, cmd.obligationId());
    if (obligation.getStatus().isClosedTerminal()) {
      throw new IllegalArgumentException("Cannot dispute a closed (cancelled/written-off) obligation");
    }
    if (obligation.getStatus() == PaymentObligationStatus.DISPUTED) {
      return obligation; // idempotent
    }
    Instant now = clock.instant();
    PaymentObligationStatus prevStatus = obligation.getStatus();
    BigDecimal prevPaid = obligation.getAmountPaid();
    BigDecimal prevRemaining = obligation.getAmountRemaining();

    obligation.markDisputed(now);
    appendEvent(obligation, PaymentObligationEventType.MARKED_DISPUTED, prevStatus, prevPaid, prevRemaining,
        bound(cmd.reasonSummary(), 280), now);
    recordObligationAudit(tenantId, obligation, "PAYMENT_OBLIGATION_DISPUTED");
    trustProfileService.applyPaymentObligationSignal(tenantId, obligation.getCustomerAccountId(),
        CounterpartySignalCode.DISPUTE_HISTORY_HIGH, TrustRiskLevel.HIGH, 10, obligation.getId(),
        "Payment obligation marked disputed.", false, true);
    return obligation;
  }

  @Transactional
  public PaymentObligation resolveDispute(ResolveDisputeCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    PaymentObligation obligation = load(tenantId, cmd.obligationId());
    if (obligation.getStatus() != PaymentObligationStatus.DISPUTED) {
      throw new IllegalArgumentException("Obligation is not disputed");
    }
    Instant now = clock.instant();
    LocalDate today = LocalDate.now(clock);
    PaymentObligationStatus prevStatus = obligation.getStatus();
    BigDecimal prevPaid = obligation.getAmountPaid();
    BigDecimal prevRemaining = obligation.getAmountRemaining();

    PaymentObligationStatus newStatus = computeActiveStatus(
        obligation.getAmountTotal(), obligation.getAmountPaid(), obligation.getDueDate(), today);
    obligation.applyStatusAndRisk(newStatus, computeRisk(newStatus, obligation.getDueDate(), today), now);
    appendEvent(obligation, PaymentObligationEventType.DISPUTE_RESOLVED, prevStatus, prevPaid, prevRemaining,
        bound(cmd.reasonSummary(), 280), now);
    recordObligationAudit(tenantId, obligation, "PAYMENT_OBLIGATION_DISPUTE_RESOLVED");
    notifyTrustOnTransition(tenantId, prevStatus, obligation, now);
    return obligation;
  }

  @Transactional
  public PaymentObligation cancelObligation(CancelObligationCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    PaymentObligation obligation = load(tenantId, cmd.obligationId());
    if (obligation.getStatus() == PaymentObligationStatus.CANCELLED) {
      return obligation; // idempotent
    }
    if (obligation.getStatus() == PaymentObligationStatus.WRITTEN_OFF) {
      throw new IllegalArgumentException("Cannot cancel a written-off obligation");
    }
    Instant now = clock.instant();
    PaymentObligationStatus prevStatus = obligation.getStatus();
    BigDecimal prevPaid = obligation.getAmountPaid();
    BigDecimal prevRemaining = obligation.getAmountRemaining();

    obligation.markClosed(PaymentObligationStatus.CANCELLED, TrustRiskLevel.LOW, now);
    appendEvent(obligation, PaymentObligationEventType.CANCELLED, prevStatus, prevPaid, prevRemaining,
        bound(cmd.reasonSummary(), 280), now);
    recordObligationAudit(tenantId, obligation, "PAYMENT_OBLIGATION_CANCELLED");
    return obligation;
  }

  @Transactional
  public PaymentObligation writeOffObligation(WriteOffObligationCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    PaymentObligation obligation = load(tenantId, cmd.obligationId());
    if (obligation.getStatus() == PaymentObligationStatus.WRITTEN_OFF) {
      return obligation; // idempotent
    }
    if (obligation.getStatus() == PaymentObligationStatus.CANCELLED) {
      throw new IllegalArgumentException("Cannot write off a cancelled obligation");
    }
    Instant now = clock.instant();
    PaymentObligationStatus prevStatus = obligation.getStatus();
    BigDecimal prevPaid = obligation.getAmountPaid();
    BigDecimal prevRemaining = obligation.getAmountRemaining();

    obligation.markClosed(PaymentObligationStatus.WRITTEN_OFF, TrustRiskLevel.HIGH, now);
    appendEvent(obligation, PaymentObligationEventType.WRITTEN_OFF, prevStatus, prevPaid, prevRemaining,
        bound(cmd.reasonSummary(), 280), now);
    recordObligationAudit(tenantId, obligation, "PAYMENT_OBLIGATION_WRITTEN_OFF");
    return obligation;
  }

  /** Deterministic status recalculation. Terminal (cancelled/written-off) and disputed are preserved. */
  @Transactional
  public PaymentObligation recalculateStatus(UUID tenantId, UUID obligationId) {
    PaymentObligation obligation = load(required(tenantId, "tenantId"), obligationId);
    if (obligation.getStatus().isClosedTerminal() || obligation.getStatus() == PaymentObligationStatus.DISPUTED) {
      return obligation;
    }
    Instant now = clock.instant();
    LocalDate today = LocalDate.now(clock);
    PaymentObligationStatus prevStatus = obligation.getStatus();
    PaymentObligationStatus newStatus = computeActiveStatus(
        obligation.getAmountTotal(), obligation.getAmountPaid(), obligation.getDueDate(), today);
    if (newStatus == prevStatus) {
      return obligation; // no transition, no event
    }
    BigDecimal prevPaid = obligation.getAmountPaid();
    BigDecimal prevRemaining = obligation.getAmountRemaining();
    obligation.applyStatusAndRisk(newStatus, computeRisk(newStatus, obligation.getDueDate(), today), now);

    boolean overdue = newStatus == PaymentObligationStatus.OVERDUE;
    appendEvent(obligation,
        overdue ? PaymentObligationEventType.OVERDUE_DETECTED : PaymentObligationEventType.STATUS_RECALCULATED,
        prevStatus, prevPaid, prevRemaining, "Status recalculated", now);
    recordObligationAudit(tenantId, obligation,
        overdue ? "PAYMENT_OBLIGATION_OVERDUE_DETECTED" : "PAYMENT_OBLIGATION_STATUS_RECALCULATED");
    notifyTrustOnTransition(tenantId, prevStatus, obligation, now);
    return obligation;
  }

  /** Bounded deterministic overdue detection for one counterparty. Returns the number newly overdue. */
  @Transactional
  public int detectOverdueForCustomer(UUID tenantId, UUID customerAccountId) {
    required(tenantId, "tenantId");
    required(customerAccountId, "customerAccountId");
    LocalDate today = LocalDate.now(clock);
    List<PaymentObligation> candidates = obligations.findOverdueCandidates(
        tenantId, customerAccountId, OVERDUE_ELIGIBLE, today, PageRequest.of(0, OVERDUE_SCAN_LIMIT));
    int newlyOverdue = 0;
    for (PaymentObligation candidate : candidates) {
      PaymentObligation updated = recalculateStatus(tenantId, candidate.getId());
      if (updated.getStatus() == PaymentObligationStatus.OVERDUE) {
        newlyOverdue++;
      }
    }
    return newlyOverdue;
  }

  // ----------------------------- read side -----------------------------

  @Transactional(readOnly = true)
  public PaymentObligationResponse getObligationView(UUID obligationId) {
    UUID tenantId = TenantContext.requireTenantId();
    PaymentObligation obligation = load(tenantId, obligationId);
    return toResponse(obligation, recentEvents(tenantId, obligation.getId(), DEFAULT_LIMIT));
  }

  @Transactional(readOnly = true)
  public List<PaymentObligationResponse> listCustomerObligations(
      UUID customerAccountId, String statusFilter, int limit) {
    UUID tenantId = TenantContext.requireTenantId();
    Pageable page = PageRequest.of(0, clampLimit(limit));
    PaymentObligationStatus status = parseStatusFilter(statusFilter);
    List<PaymentObligation> rows = status == null
        ? obligations.findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(tenantId, customerAccountId, page)
        : obligations.findByTenantIdAndCustomerAccountIdAndStatusOrderByCreatedAtDesc(
            tenantId, customerAccountId, status, page);
    return rows.stream().map(o -> toResponse(o, List.of())).toList();
  }

  @Transactional(readOnly = true)
  public CustomerPaymentSummaryResponse getCustomerPaymentSummary(UUID customerAccountId) {
    UUID tenantId = TenantContext.requireTenantId();

    Map<PaymentObligationStatus, Long> counts = new EnumMap<>(PaymentObligationStatus.class);
    BigDecimal totalOpen = zeroMoney();
    BigDecimal totalOverdue = zeroMoney();
    BigDecimal totalPaid = zeroMoney();
    for (PaymentObligationStatusAggregate agg : obligations.aggregateByStatus(tenantId, customerAccountId)) {
      counts.put(agg.getStatus(), agg.getObligationCount());
      totalPaid = totalPaid.add(nz(agg.getPaidAmount()));
      if (agg.getStatus() == PaymentObligationStatus.OVERDUE) {
        totalOverdue = totalOverdue.add(nz(agg.getRemainingAmount()));
      } else if (agg.getStatus() == PaymentObligationStatus.OPEN
          || agg.getStatus() == PaymentObligationStatus.PARTIALLY_PAID) {
        totalOpen = totalOpen.add(nz(agg.getRemainingAmount()));
      }
    }

    // Single-currency summary is meaningful; mixed-currency amounts are withheld (counts stay valid).
    List<String> currencies = obligations.findDistinctCurrencies(tenantId, customerAccountId);
    String currency;
    boolean amountsMeaningful;
    if (currencies.isEmpty()) {
      currency = null;
      amountsMeaningful = true;
    } else if (currencies.size() == 1) {
      currency = currencies.get(0);
      amountsMeaningful = true;
    } else {
      currency = "MIXED";
      amountsMeaningful = false;
    }

    CounterpartyTrustProfile profile =
        trustProfiles.findByTenantIdAndCustomerAccountId(tenantId, customerAccountId).orElse(null);
    Integer paymentReliabilityScore = profile == null ? null : profile.getPaymentReliabilityScore();
    Instant lastPaymentAt = profile == null ? null : profile.getLastPaymentAt();
    List<CounterpartyTrustSignalView> recentSignals =
        trustProfileService.listRecentSignals(customerAccountId, RECENT_SIGNAL_LIMIT);

    return new CustomerPaymentSummaryResponse(
        customerAccountId,
        currency,
        amountsMeaningful ? totalOpen : null,
        amountsMeaningful ? totalOverdue : null,
        amountsMeaningful ? totalPaid : null,
        counts.getOrDefault(PaymentObligationStatus.OPEN, 0L),
        counts.getOrDefault(PaymentObligationStatus.PARTIALLY_PAID, 0L),
        counts.getOrDefault(PaymentObligationStatus.OVERDUE, 0L),
        counts.getOrDefault(PaymentObligationStatus.DISPUTED, 0L),
        counts.getOrDefault(PaymentObligationStatus.PAID, 0L),
        counts.getOrDefault(PaymentObligationStatus.CANCELLED, 0L),
        counts.getOrDefault(PaymentObligationStatus.WRITTEN_OFF, 0L),
        lastPaymentAt,
        paymentReliabilityScore,
        recentSignals);
  }

  // ----------------------------- deterministic engine -----------------------------

  /** Status for a non-terminal, non-disputed obligation. Overpayment is rejected before this runs. */
  PaymentObligationStatus computeActiveStatus(
      BigDecimal amountTotal, BigDecimal amountPaid, LocalDate dueDate, LocalDate today) {
    if (amountPaid.compareTo(amountTotal) >= 0) {
      return PaymentObligationStatus.PAID;
    }
    boolean overdue = dueDate != null && dueDate.isBefore(today);
    if (amountPaid.signum() == 0) {
      return overdue ? PaymentObligationStatus.OVERDUE : PaymentObligationStatus.OPEN;
    }
    return overdue ? PaymentObligationStatus.OVERDUE : PaymentObligationStatus.PARTIALLY_PAID;
  }

  TrustRiskLevel computeRisk(PaymentObligationStatus status, LocalDate dueDate, LocalDate today) {
    return switch (status) {
      case PAID, OPEN, CANCELLED -> TrustRiskLevel.LOW;
      case PARTIALLY_PAID -> TrustRiskLevel.MEDIUM;
      case DISPUTED, WRITTEN_OFF -> TrustRiskLevel.HIGH;
      case OVERDUE -> overdueRisk(dueDate, today);
    };
  }

  private TrustRiskLevel overdueRisk(LocalDate dueDate, LocalDate today) {
    if (dueDate == null) {
      return TrustRiskLevel.MEDIUM;
    }
    long daysOverdue = ChronoUnit.DAYS.between(dueDate, today);
    if (daysOverdue <= 0) {
      return TrustRiskLevel.MEDIUM;
    }
    return daysOverdue <= OVERDUE_MEDIUM_MAX_DAYS ? TrustRiskLevel.MEDIUM : TrustRiskLevel.HIGH;
  }

  // ----------------------------- trust integration -----------------------------

  private void notifyTrustOnTransition(
      UUID tenantId, PaymentObligationStatus previousStatus, PaymentObligation obligation, Instant now) {
    PaymentObligationStatus next = obligation.getStatus();
    if (next == previousStatus) {
      return;
    }
    if (next == PaymentObligationStatus.OVERDUE) {
      trustProfileService.applyPaymentObligationSignal(tenantId, obligation.getCustomerAccountId(),
          CounterpartySignalCode.PAYMENT_OVERDUE, obligation.getRiskLevel(), 7, obligation.getId(),
          "Payment obligation is overdue.", true, false);
    } else if (next == PaymentObligationStatus.PARTIALLY_PAID) {
      trustProfileService.applyPaymentObligationSignal(tenantId, obligation.getCustomerAccountId(),
          CounterpartySignalCode.PARTIAL_PAYMENT_OPEN, TrustRiskLevel.MEDIUM, 3, obligation.getId(),
          "Partial payment received; balance remains open.", false, false);
    }
  }

  // ----------------------------- helpers -----------------------------

  private PaymentObligation load(UUID tenantId, UUID obligationId) {
    return obligations.findByIdAndTenantId(required(obligationId, "obligationId"), tenantId)
        .orElseThrow(() -> new NotFoundException("Payment obligation not found"));
  }

  private void appendEvent(PaymentObligation obligation, PaymentObligationEventType eventType,
      PaymentObligationStatus previousStatus, BigDecimal previousAmountPaid, BigDecimal previousAmountRemaining,
      String reasonSummary, Instant now) {
    events.save(new PaymentObligationEvent(
        obligation.getTenantId(), obligation.getId(), obligation.getCustomerAccountId(), eventType,
        previousStatus, obligation.getStatus(), obligation.getRiskLevel(), previousAmountPaid,
        obligation.getAmountPaid(), previousAmountRemaining, obligation.getAmountRemaining(),
        bound(reasonSummary, 280), obligation.getSourceType(), obligation.getSourceRefId(), now));
  }

  private List<PaymentObligationEventResponse> recentEvents(UUID tenantId, UUID obligationId, int limit) {
    return events
        .findByTenantIdAndPaymentObligationIdOrderByCreatedAtDesc(tenantId, obligationId, PageRequest.of(0, clampLimit(limit)))
        .stream()
        .map(e -> new PaymentObligationEventResponse(
            e.getEventType().name(),
            e.getPreviousStatus() == null ? null : e.getPreviousStatus().name(),
            e.getNewStatus().name(),
            e.getNewAmountPaid(),
            e.getNewAmountRemaining(),
            e.getReasonSummary(),
            e.getCreatedAt()))
        .toList();
  }

  private PaymentObligationResponse toResponse(PaymentObligation o, List<PaymentObligationEventResponse> recent) {
    return new PaymentObligationResponse(
        o.getId(), o.getCustomerAccountId(), o.getObligationNumber(), o.getExternalReference(),
        o.getAmountTotal(), o.getAmountPaid(), o.getAmountRemaining(), o.getCurrency(), o.getDueDate(),
        o.getStatus().name(), o.getRiskLevel().name(), o.getLastPaymentAt(), o.getCreatedAt(),
        o.getUpdatedAt(), recent);
  }

  private void recordObligationAudit(UUID tenantId, PaymentObligation obligation, String action) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("obligationId", obligation.getId().toString());
    metadata.put("customerAccountId", obligation.getCustomerAccountId().toString());
    metadata.put("status", obligation.getStatus().name());
    metadata.put("riskLevel", obligation.getRiskLevel().name());
    metadata.put("amountRemaining", obligation.getAmountRemaining().toPlainString());
    metadata.put("currency", obligation.getCurrency());
    AuditEvent event = new AuditEvent(tenantId, null, action, "PaymentObligation",
        obligation.getId().toString(), jsonSupport.writeObject(metadata), clock.instant());
    auditEvents.save(event);
  }

  private PaymentObligationStatus parseStatusFilter(String statusFilter) {
    if (statusFilter == null || statusFilter.isBlank()) {
      return null;
    }
    try {
      return PaymentObligationStatus.valueOf(statusFilter.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown payment obligation status filter: " + statusFilter);
    }
  }

  static int clampLimit(int requested) {
    if (requested <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  private BigDecimal normalizeAmount(BigDecimal value, String field, boolean strictlyPositive) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    BigDecimal scaled = value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (strictlyPositive) {
      if (scaled.signum() <= 0) {
        throw new IllegalArgumentException(field + " must be greater than zero");
      }
    } else if (scaled.signum() < 0) {
      throw new IllegalArgumentException(field + " must not be negative");
    }
    return scaled;
  }

  private String normalizeCurrency(String value) {
    if (value == null) {
      throw new IllegalArgumentException("currency is required");
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (!CURRENCY_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException("currency must be a 3-letter ISO-like code");
    }
    return normalized;
  }

  private static BigDecimal zeroMoney() {
    return BigDecimal.ZERO.setScale(MONEY_SCALE);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? zeroMoney() : value;
  }

  private static String bound(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }

  private static <T> T required(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
