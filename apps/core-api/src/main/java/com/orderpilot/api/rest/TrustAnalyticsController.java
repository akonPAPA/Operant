package com.orderpilot.api.rest;

import com.orderpilot.api.dto.TrustAnalyticsDtos.CounterpartyTrustDashboardDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.DocumentAnomalyTrendDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.OutstandingDebtItemDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.TrustAnalyticsRebuildResponseDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.TrustRiskDistributionDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.TrustReviewQueueItemDto;
import com.orderpilot.application.services.trust.TrustAnalyticsProjectionService;
import com.orderpilot.application.services.trust.TrustAnalyticsQueryService;
import com.orderpilot.common.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-17E Trust Analytics Read Models.
 *
 * Tenant-scoped, bounded analytics read surface under {@code /api/v1/trust/analytics}. GET reads require
 * {@code TRUST_ANALYTICS_READ}; the bounded tenant rebuild requires the stronger
 * {@code TRUST_ANALYTICS_REBUILD} (see {@code ApiPermissionInterceptor}). Tenant is resolved from context
 * in the services; path/query ids are never trusted across tenants. These endpoints serve DERIVED read
 * models only — the OP-CAP-17A/17B/17C/17D operational records remain the system of record and are never
 * mutated here. No raw document/OCR/prompt text, bank credentials, account numbers, or secrets are
 * returned.
 */
@RestController
public class TrustAnalyticsController {
  private final TrustAnalyticsQueryService queryService;
  private final TrustAnalyticsProjectionService projectionService;

  public TrustAnalyticsController(
      TrustAnalyticsQueryService queryService, TrustAnalyticsProjectionService projectionService) {
    this.queryService = queryService;
    this.projectionService = projectionService;
  }

  @GetMapping("/api/v1/trust/analytics/review-queue")
  public List<TrustReviewQueueItemDto> reviewQueue(
      @RequestParam(name = "riskLevel", required = false) String riskLevel,
      @RequestParam(name = "approvalStatus", required = false) String approvalStatus,
      @RequestParam(name = "blocking", required = false) Boolean blocking,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size) {
    return queryService.listReviewQueue(riskLevel, approvalStatus, blocking, page, size);
  }

  @GetMapping("/api/v1/trust/analytics/counterparties/{counterpartyId}")
  public CounterpartyTrustDashboardDto counterpartyDashboard(@PathVariable UUID counterpartyId) {
    return queryService.getCounterpartyDashboard(counterpartyId);
  }

  @GetMapping("/api/v1/trust/analytics/outstanding-debt")
  public List<OutstandingDebtItemDto> outstandingDebt(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "riskLevel", required = false) String riskLevel,
      @RequestParam(name = "counterpartyId", required = false) UUID counterpartyId,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size) {
    return queryService.listOutstandingDebt(status, riskLevel, counterpartyId, page, size);
  }

  @GetMapping("/api/v1/trust/analytics/document-anomalies")
  public List<DocumentAnomalyTrendDto> documentAnomalies(
      @RequestParam(name = "fromPeriod", required = false) String fromPeriod,
      @RequestParam(name = "toPeriod", required = false) String toPeriod,
      @RequestParam(name = "signalCode", required = false) String signalCode,
      @RequestParam(name = "severity", required = false) String severity,
      @RequestParam(name = "limit", defaultValue = "25") int limit) {
    return queryService.listDocumentAnomalies(fromPeriod, toPeriod, signalCode, severity, limit);
  }

  @GetMapping("/api/v1/trust/analytics/risk-distribution")
  public List<TrustRiskDistributionDto> riskDistribution(
      @RequestParam(name = "periodKey", required = false) String periodKey,
      @RequestParam(name = "fromPeriod", required = false) String fromPeriod,
      @RequestParam(name = "toPeriod", required = false) String toPeriod,
      @RequestParam(name = "limit", defaultValue = "25") int limit) {
    return queryService.listRiskDistribution(periodKey, fromPeriod, toPeriod, limit);
  }

  /**
   * Bounded tenant rebuild — refreshes today's period aggregates plus a bounded page of recent
   * decisions/counterparties/obligations. Stronger permission than the reads. The {@code tenantOnly}
   * flag is required to be true; cross-tenant/global rebuild is not supported (non-goal).
   */
  @PostMapping("/api/v1/trust/analytics/rebuild")
  public TrustAnalyticsRebuildResponseDto rebuild(
      @RequestParam(name = "tenantOnly", defaultValue = "true") boolean tenantOnly) {
    if (!tenantOnly) {
      throw new IllegalArgumentException("Only tenant-scoped rebuild is supported (tenantOnly=true)");
    }
    UUID tenantId = TenantContext.requireTenantId();
    return projectionService.rebuildAllForTenant(tenantId);
  }
}
