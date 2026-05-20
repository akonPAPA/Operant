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
}
