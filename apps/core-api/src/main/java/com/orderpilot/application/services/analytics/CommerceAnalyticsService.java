package com.orderpilot.application.services.analytics;

import com.orderpilot.api.dto.Stage8Dtos.CommerceAnalyticsSummaryResponse;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.bot.BotMessageRepository;
import com.orderpilot.domain.bot.BotRfqRequestRepository;
import com.orderpilot.domain.reconciliation.ReconciliationCaseRepository;
import com.orderpilot.domain.reconciliation.ReconciliationSeverity;
import com.orderpilot.domain.reconciliation.ReconciliationStatus;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceAnalyticsService {
  private final DraftOrderRepository draftOrderRepository;
  private final BotRfqRequestRepository botRfqRequestRepository;
  private final BotMessageRepository botMessageRepository;
  private final ReconciliationCaseRepository reconciliationCaseRepository;
  private final Clock clock;

  public CommerceAnalyticsService(DraftOrderRepository draftOrderRepository, BotRfqRequestRepository botRfqRequestRepository, BotMessageRepository botMessageRepository, ReconciliationCaseRepository reconciliationCaseRepository, Clock clock) {
    this.draftOrderRepository = draftOrderRepository;
    this.botRfqRequestRepository = botRfqRequestRepository;
    this.botMessageRepository = botMessageRepository;
    this.reconciliationCaseRepository = reconciliationCaseRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public CommerceAnalyticsSummaryResponse summary() {
    UUID tenantId = TenantContext.requireTenantId();
    Map<String, Long> channelBreakdown = new LinkedHashMap<>();
    channelBreakdown.put("TELEGRAM", botMessageRepository.countByTenantIdAndChannel(tenantId, "TELEGRAM"));
    return new CommerceAnalyticsSummaryResponse(
        tenantId,
        BigDecimal.ZERO,
        "TODO: totalSalesAmount remains 0 until invoice/sales mirror records are added.",
        draftOrderRepository.countByTenantId(tenantId),
        botRfqRequestRepository.countByTenantId(tenantId),
        reconciliationCaseRepository.countByTenantIdAndStatus(tenantId, ReconciliationStatus.OPEN),
        reconciliationCaseRepository.countByTenantIdAndSeverityAndStatus(tenantId, ReconciliationSeverity.HIGH, ReconciliationStatus.OPEN),
        channelBreakdown,
        clock.instant()
    );
  }
}
