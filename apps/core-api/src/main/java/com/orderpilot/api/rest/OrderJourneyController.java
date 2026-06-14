package com.orderpilot.api.rest;

import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyAttentionSummaryDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyDetailDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneySummaryDto;
import com.orderpilot.api.dto.OrderJourneyDtos.RecordFulfillmentSignalRequest;
import com.orderpilot.application.services.journey.OrderJourneyReadService;
import com.orderpilot.application.services.journey.OrderJourneyService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-22 — Order Journey & Fulfillment Visibility.
 *
 * <p>Reads are tenant-scoped and require ANALYTICS_READ (via {@code ApiPermissionInterceptor}). The
 * single mutation — recording an internal fulfillment signal — requires REVIEW_ACTION (an audited
 * operator action) and performs no external/connector/ERP/payment write. {@code by-source}
 * idempotently materializes the derived projection (no business mutation). Errors flow through the
 * existing {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/order-journeys")
public class OrderJourneyController {
  private final OrderJourneyReadService readService;
  private final OrderJourneyService journeyService;
  private final RequestActorResolver actorResolver;

  public OrderJourneyController(OrderJourneyReadService readService, OrderJourneyService journeyService,
      RequestActorResolver actorResolver) {
    this.readService = readService;
    this.journeyService = journeyService;
    this.actorResolver = actorResolver;
  }

  @GetMapping
  public OrderJourneySummaryDto list(@RequestParam(defaultValue = "0") int limit) {
    return readService.list(limit);
  }

  @GetMapping("/attention")
  public OrderJourneyAttentionSummaryDto attention(@RequestParam(defaultValue = "0") int limit) {
    return readService.attention(limit);
  }

  @GetMapping("/{id}")
  public OrderJourneyDetailDto get(@PathVariable UUID id) {
    return readService.detail(id);
  }

  @GetMapping("/by-source")
  public OrderJourneyDetailDto bySource(@RequestParam JourneySourceType sourceType, @RequestParam UUID sourceId) {
    OrderJourney journey = journeyService.ensureJourney(sourceType, sourceId);
    return readService.detailByEntity(journey);
  }

  @PostMapping("/{id}/signals")
  public OrderJourneyDetailDto recordSignal(@PathVariable UUID id,
      @RequestBody RecordFulfillmentSignalRequest request, HttpServletRequest http) {
    UUID actorId = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    OrderJourney journey = journeyService.recordSignal(id, request, actorId);
    return readService.detailByEntity(journey);
  }
}
