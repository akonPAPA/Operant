package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * OP-CAP-17E Trust Analytics Read Models.
 *
 * Bounded, read-only analytics DTOs served from the derived trust read models. They expose only safe,
 * deterministic, business-facing fields (risk level/score, status, bounded reason codes, counts, and
 * single-currency amounts). They NEVER expose raw document/OCR/prompt text, bank credentials, IBAN,
 * routing/account numbers, PAN/CVV, card/NFC payloads, internal audit payloads, or per-line margin/cost.
 * Amounts that would mix currencies are withheld (currency reported as {@code MIXED}); no FX is performed.
 */
public final class TrustAnalyticsDtos {
  private TrustAnalyticsDtos() {}

  public record TrustReviewQueueItemDto(
      UUID id,
      UUID trustRiskDecisionId,
      String subjectType,
      UUID subjectId,
      UUID counterpartyId,
      UUID documentTrustRunId,
      UUID paymentObligationId,
      String riskLevel,
      int riskScore,
      String action,
      boolean blocking,
      boolean humanReviewRequired,
      String approvalStatus,
      String topReasonCode,
      String reasonSummary,
      Instant createdAt,
      Instant updatedAt,
      Instant lastProjectedAt) {}

  public record CounterpartyTrustDashboardDto(
      UUID counterpartyId,
      int trustScore,
      String trustTier,
      long orderCount,
      long completedOrderCount,
      long paidOnTimeCount,
      long overdueCount,
      long disputedCount,
      long highRiskDocumentCount,
      long criticalRiskDocumentCount,
      long highRiskDecisionCount,
      long criticalRiskDecisionCount,
      long openPaymentObligationCount,
      long overduePaymentObligationCount,
      BigDecimal outstandingAmount,
      String primaryCurrency,
      Instant lastOrderAt,
      Instant lastPaymentAt,
      Instant lastHighRiskAt,
      Instant lastCriticalRiskAt,
      Instant updatedAt,
      Instant lastProjectedAt) {}

  public record OutstandingDebtItemDto(
      UUID id,
      UUID paymentObligationId,
      UUID counterpartyId,
      UUID orderId,
      UUID invoiceMirrorId,
      String externalReference,
      BigDecimal amountTotal,
      BigDecimal amountPaid,
      BigDecimal amountRemaining,
      String currency,
      LocalDate dueDate,
      String status,
      String riskLevel,
      int daysOverdue,
      UUID linkedRiskDecisionId,
      String topReasonCode,
      Instant createdAt,
      Instant updatedAt,
      Instant lastProjectedAt) {}

  public record DocumentAnomalyTrendDto(
      String periodKey,
      Instant periodStart,
      Instant periodEnd,
      String signalCode,
      String severity,
      String riskLevel,
      UUID counterpartyId,
      long count,
      long highCount,
      long criticalCount,
      Instant latestSeenAt,
      Instant lastProjectedAt) {}

  public record TrustRiskDistributionDto(
      String periodKey,
      Instant periodStart,
      Instant periodEnd,
      long lowCount,
      long mediumCount,
      long highCount,
      long criticalCount,
      long approvalRequiredCount,
      long blockingCount,
      long overrideCount,
      BigDecimal avgRiskScore,
      Instant lastProjectedAt) {}

  /** Returned from the bounded tenant rebuild endpoint — counts what was refreshed, never raw data. */
  public record TrustAnalyticsRebuildResponseDto(
      String periodKey,
      int reviewQueueRowsProjected,
      int counterpartyDashboardsProjected,
      int outstandingDebtRowsProjected,
      int documentAnomalyTrendRowsProjected,
      boolean riskDistributionProjected,
      Instant rebuiltAt) {}
}
