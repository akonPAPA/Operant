package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Stage8Dtos {
  private Stage8Dtos() {}

  public record InventoryReconciliationRunRequest(UUID productId, UUID locationId) {}

  public record ReconciliationCaseResponse(
      UUID id,
      UUID tenantId,
      UUID productId,
      UUID locationId,
      BigDecimal expectedStock,
      BigDecimal actualStock,
      BigDecimal mismatchQuantity,
      String severity,
      String status,
      String likelyCauses,
      Instant calculatedAt,
      Instant createdAt,
      Instant updatedAt
  ) {}

  public record ReconciliationRunResponse(
      UUID tenantId,
      UUID productId,
      UUID locationId,
      BigDecimal expectedStock,
      BigDecimal actualStock,
      BigDecimal mismatchQuantity,
      String severity,
      String status,
      UUID reconciliationCaseId,
      boolean discrepancyCreatedOrUpdated,
      Instant calculatedAt
  ) {}

  public record ReconciliationCaseStatusRequest(String status) {}

  public record ReconciliationCasesResponse(
      List<ReconciliationCaseResponse> cases,
      int page,
      int size,
      long totalElements,
      int totalPages
  ) {}

  public record CommerceAnalyticsSummaryResponse(
      UUID tenantId,
      BigDecimal totalSalesAmount,
      String totalSalesAmountNote,
      long totalOrders,
      long totalBotRfqRequests,
      long openReconciliationCases,
      long highSeverityReconciliationCases,
      Map<String, Long> channelBreakdown,
      Instant generatedAt
  ) {}

  public record AnalyticsOverviewResponse(
      UUID tenantId,
      IntakeAnalyticsResponse intake,
      ExtractionAnalyticsResponse extraction,
      ValidationAnalyticsResponse validation,
      ReviewAnalyticsResponse review,
      BotAnalyticsResponse bot,
      WorkflowHealthAnalyticsResponse workflowHealth,
      List<AutomationReadinessIndicator> automationReadiness,
      Instant generatedAt
  ) {}

  public record IntakeAnalyticsResponse(
      UUID tenantId,
      long totalInboundDocuments,
      long totalChannelMessages,
      long totalWebhookEvents,
      long duplicateOrReplayEvents,
      long processingBacklog,
      Map<String, Long> volumeByChannel,
      Map<String, Long> processingJobsByStatus,
      Instant generatedAt
  ) {}

  public record ExtractionAnalyticsResponse(
      UUID tenantId,
      Map<String, Long> extractionRunsByStatus,
      BigDecimal averageDocumentConfidence,
      long lowConfidenceExtractionCount,
      long extractedLineItemCount,
      Instant generatedAt
  ) {}

  public record ValidationAnalyticsResponse(
      UUID tenantId,
      Map<String, Long> validationRunsByStatus,
      Map<String, Long> topIssueCodes,
      long blockedCount,
      long needsReviewCount,
      long passedCount,
      Map<String, Long> approvalRequirementsByReason,
      Instant generatedAt
  ) {}

  public record ReviewAnalyticsResponse(
      UUID tenantId,
      Map<String, Long> reviewCasesByStatus,
      long openReviewBacklog,
      long escalatedCases,
      long correctionRequestedCases,
      long approvedForNextStageCases,
      Instant generatedAt
  ) {}

  public record BotAnalyticsResponse(
      UUID tenantId,
      Map<String, Long> botConversationsByStatus,
      Map<String, Long> intentsByType,
      long handoffCount,
      long needsReviewCount,
      long unknownIntentCount,
      Instant generatedAt
  ) {}

  public record WorkflowHealthAnalyticsResponse(
      UUID tenantId,
      Map<String, Long> processingJobsByStatus,
      long failedJobs,
      long staleJobs,
      long recentAuditEventCount,
      long recentOperatorActionCount,
      Instant generatedAt
  ) {}

  public record AutomationReadinessIndicator(String key, String status, String reason) {}

  public record Stage8CommandCenterAnalyticsResponse(
      UUID tenantId,
      long totalInboundRequests,
      long botOnlyHandoffCount,
      long validationBackedReviewCount,
      long blockedUnsafeDraftAttempts,
      BigDecimal exceptionRate,
      BigDecimal automationRate,
      long draftsPrepared,
      Map<String, Long> channelMix,
      Instant generatedAt
  ) {}

  public record Stage8ChannelVolumeResponse(
      UUID tenantId,
      Map<String, Long> requestVolumeByChannel,
      long totalInboundRequests,
      Instant generatedAt
  ) {}

  public record Stage8OperatorReviewAnalyticsResponse(
      UUID tenantId,
      long validationBackedReviewCount,
      long botOnlyHandoffCount,
      long openExceptionCount,
      long blockedUnsafeDraftAttempts,
      BigDecimal averageReviewCycleHours,
      long discountRiskCount,
      long marginRiskCount,
      Instant generatedAt
  ) {}

  public record Stage8BotHandoffAnalyticsResponse(
      UUID tenantId,
      long botOnlyHandoffCount,
      long openBotHandoffCount,
      long blockedBotOnlyDraftPreparationCount,
      Map<String, Long> botHandoffsByStatus,
      Instant generatedAt
  ) {}

  public record ReconciliationFormulaComponent(String movementType, BigDecimal quantity, boolean supported) {}

  public record Stage8ReconciliationSummaryResponse(
      UUID tenantId,
      long inventoryMismatchCount,
      long highSeverityDiscrepancyCount,
      long staleInventoryCount,
      long lowStockCount,
      long openReconciliationCases,
      long movementMirrorCount,
      List<String> unsupportedMovementTypes,
      Instant generatedAt
  ) {}

  public record Stage8InventoryMovementResponse(
      UUID id,
      UUID productId,
      UUID locationId,
      String movementType,
      BigDecimal quantity,
      Instant occurredAt,
      String sourceType,
      String sourceReference
  ) {}

  public record Stage8ProductTimelineResponse(
      UUID tenantId,
      UUID productId,
      List<Stage8InventoryMovementResponse> movements,
      Instant generatedAt
  ) {}

  public record Stage8ReconciliationRefreshResponse(
      UUID tenantId,
      long productLocationPairsEvaluated,
      long casesCreatedOrUpdated,
      long staleInventoryWarnings,
      boolean inventoryMutated,
      boolean connectorCommandsCreated,
      Instant generatedAt
  ) {}

  public record RoiAssumptionsResponse(
      UUID tenantId,
      BigDecimal averageManualHandlingMinutesPerRequest,
      BigDecimal averageFullyLoadedOperatorHourlyCost,
      String defaultCurrency,
      String valueAttributionMode,
      boolean defaultAssumptions,
      Instant updatedAt
  ) {}

  public record RoiAssumptionsRequest(
      BigDecimal averageManualHandlingMinutesPerRequest,
      BigDecimal averageFullyLoadedOperatorHourlyCost,
      String defaultCurrency,
      String valueAttributionMode
  ) {}

  public record Stage8ValueSummaryResponse(
      UUID tenantId,
      BigDecimal estimatedOperatorHoursSaved,
      BigDecimal estimatedLaborCostSaved,
      BigDecimal averageReviewCycleHours,
      BigDecimal averageDraftPreparationCycleHours,
      long blockedUnsafeDraftAttempts,
      long discountLeakageCount,
      BigDecimal estimatedDiscountLeakageValue,
      long marginRiskCount,
      BigDecimal estimatedMarginRiskImpact,
      BigDecimal substituteRecoveredRevenue,
      BigDecimal inventoryDiscrepancyValue,
      long staleInventoryRiskCount,
      String currency,
      boolean estimated,
      boolean defaultAssumptions,
      Instant generatedAt
  ) {}

  public record Stage8ValueLeakageResponse(
      UUID tenantId,
      long discountLeakageCount,
      BigDecimal estimatedDiscountLeakageValue,
      long marginRiskCount,
      BigDecimal estimatedMarginRiskImpact,
      BigDecimal inventoryDiscrepancyValue,
      long staleInventoryRiskCount,
      Map<String, Long> exceptionCausesBreakdown,
      Map<String, Long> topReconciliationIssues,
      String currency,
      boolean estimated,
      Instant generatedAt
  ) {}

  public record Stage8ValueProductivityResponse(
      UUID tenantId,
      long totalInboundRequests,
      BigDecimal automationRate,
      BigDecimal exceptionRate,
      BigDecimal estimatedOperatorHoursSaved,
      BigDecimal estimatedLaborCostSaved,
      BigDecimal averageReviewCycleHours,
      BigDecimal averageDraftPreparationCycleHours,
      long draftQuoteCount,
      long draftOrderCount,
      long blockedUnsafeDraftAttempts,
      String currency,
      boolean estimated,
      Instant generatedAt
  ) {}

  public record Stage8PilotRoiReportResponse(
      UUID tenantId,
      Instant from,
      Instant to,
      long totalInboundRequests,
      BigDecimal automationRate,
      BigDecimal exceptionRate,
      long botHandoffs,
      long draftQuoteCount,
      long draftOrderCount,
      long blockedUnsafeAttempts,
      BigDecimal estimatedHoursSaved,
      BigDecimal estimatedLaborCostSaved,
      long marginRiskCount,
      long discountLeakageCount,
      BigDecimal inventoryDiscrepancyValue,
      Map<String, Long> topExceptionCategories,
      Map<String, Long> topReconciliationIssues,
      RoiAssumptionsResponse assumptions,
      boolean exportable,
      Instant generatedAt
  ) {}
}
