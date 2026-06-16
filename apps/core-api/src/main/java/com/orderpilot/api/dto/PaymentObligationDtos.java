package com.orderpilot.api.dto;

import com.orderpilot.api.dto.TrustDtos.CounterpartyTrustSignalView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * Bounded, read-only payment obligation DTOs. These expose only safe deterministic status/risk and
 * bounded business identifiers ({@code obligationNumber}, {@code externalReference}). They NEVER expose
 * raw bank credentials, IBAN, routing/account numbers, PAN/CVV, card numbers, NFC payloads, raw bank
 * statement payloads, raw document text, prompt text, or internal audit payloads.
 */
public final class PaymentObligationDtos {
  private PaymentObligationDtos() {}

  public record PaymentObligationEventResponse(
      String eventType,
      String previousStatus,
      String newStatus,
      BigDecimal newAmountPaid,
      BigDecimal newAmountRemaining,
      String reasonSummary,
      Instant createdAt) {}

  public record PaymentObligationResponse(
      UUID id,
      UUID customerAccountId,
      String obligationNumber,
      String externalReference,
      BigDecimal amountTotal,
      BigDecimal amountPaid,
      BigDecimal amountRemaining,
      String currency,
      LocalDate dueDate,
      String status,
      String riskLevel,
      Instant lastPaymentAt,
      Instant createdAt,
      Instant updatedAt,
      List<PaymentObligationEventResponse> recentEvents) {}

  /**
   * Bounded counterparty payment summary. Amounts are populated only when the counterparty's
   * obligations share a single currency; for mixed currencies {@code currency} is {@code "MIXED"} and
   * the amount fields are {@code null} (status counts remain valid). {@code recentSignals} reuses the
   * OP-CAP-17B bounded signal view.
   */
  public record CustomerPaymentSummaryResponse(
      UUID customerAccountId,
      String currency,
      BigDecimal totalOpenAmount,
      BigDecimal totalOverdueAmount,
      BigDecimal totalPaidAmount,
      long openCount,
      long partiallyPaidCount,
      long overdueCount,
      long disputedCount,
      long paidCount,
      long cancelledCount,
      long writtenOffCount,
      Instant lastPaymentAt,
      Integer paymentReliabilityScore,
      List<CounterpartyTrustSignalView> recentSignals) {}
}
