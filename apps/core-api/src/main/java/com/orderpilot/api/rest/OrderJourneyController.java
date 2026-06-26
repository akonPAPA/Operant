package com.orderpilot.api.rest;

import com.orderpilot.api.dto.OrderJourneyDtos.CustomerSafeJourneyDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OperatorFulfillmentTimelineResponse;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyAttentionSummaryDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyDetailDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneySummaryDto;
import com.orderpilot.api.dto.OrderJourneyDtos.CreateTrackingLinkRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.RecordFulfillmentSignalRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.RecordManualMilestoneRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.RevokeTrackingLinkRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkCreatedDto;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkListDto;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkRevokedDto;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.JourneyProjectionHealthDto;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.JourneyProjectionRequest;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.JourneyProjectionRequestResponse;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.OrderJourneyProjectionDrainSummary;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.ProcessJourneyProjectionResponse;
import com.orderpilot.application.services.journey.OrderJourneyProjectionDrainService;
import com.orderpilot.application.services.journey.OrderJourneyProjectionPublisher;
import com.orderpilot.application.services.journey.OrderJourneyProjectorRunner;
import com.orderpilot.application.services.journey.OrderJourneyReadService;
import com.orderpilot.application.services.journey.OrderJourneyService;
import com.orderpilot.application.services.journey.OrderJourneyTrackingLinkService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-22 / OP-CAP-23 — Order Journey &amp; Fulfillment Visibility, with an event/outbox-driven projector.
 *
 * <p>Reads are tenant-scoped and require ANALYTICS_READ (via {@code ApiPermissionInterceptor}); mutations
 * under this prefix require REVIEW_ACTION (an audited operator action) and perform no external/connector/
 * ERP/payment write. OP-CAP-23 adds a durable, idempotent projection path: {@code by-source} now prefers an
 * already-projected journey and only materializes on read as a documented temporary fallback (tagged
 * {@code projectionSource=ON_READ_FALLBACK}), while requesting a durable refresh so the projector can take
 * over. Bounded projector control/observability endpoints live under the same prefix. Errors flow through
 * the existing {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/order-journeys")
public class OrderJourneyController {
  private final OrderJourneyReadService readService;
  private final OrderJourneyService journeyService;
  private final OrderJourneyProjectorRunner projectorRunner;
  private final OrderJourneyProjectionPublisher projectionPublisher;
  private final OrderJourneyProjectionDrainService drainService;
  private final OrderJourneyTrackingLinkService trackingLinkService;
  private final RequestActorResolver actorResolver;

  public OrderJourneyController(OrderJourneyReadService readService, OrderJourneyService journeyService,
      OrderJourneyProjectorRunner projectorRunner, OrderJourneyProjectionPublisher projectionPublisher,
      OrderJourneyProjectionDrainService drainService, OrderJourneyTrackingLinkService trackingLinkService,
      RequestActorResolver actorResolver) {
    this.readService = readService;
    this.journeyService = journeyService;
    this.projectorRunner = projectorRunner;
    this.projectionPublisher = projectionPublisher;
    this.drainService = drainService;
    this.trackingLinkService = trackingLinkService;
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

  /** OP-CAP-23 — bounded, tenant-scoped projector health (pending/failed/dead-lettered + recent failures). */
  @GetMapping("/projection-health")
  public JourneyProjectionHealthDto projectionHealth() {
    return drainService.health(TenantContext.requireTenantId());
  }

  @GetMapping("/{id}")
  public OrderJourneyDetailDto get(@PathVariable UUID id) {
    return readService.detail(id);
  }

  /** OP-CAP-46B — customer-safe tracking view. Excludes internal-only milestones, events, risk, and status. */
  @GetMapping("/{id}/customer-safe")
  public CustomerSafeJourneyDto getCustomerSafe(@PathVariable UUID id) {
    return readService.customerSafe(id);
  }

  /**
   * OP-CAP-47A — operator fulfillment visibility timeline. Protected operator read (ANALYTICS_READ via
   * the GET order-journey prefix rule); tenant from {@code X-Tenant-Id}, journey from the path. Returns
   * a safe, tenant-scoped journey summary plus its ingested fulfillment signals as a deterministically
   * ordered timeline. Read-only — no milestone/signal/order-state mutation and no external write. The
   * response never carries raw payload refs, external/idempotency keys, signal entity ids, audit
   * internals, or any cross-tenant data.
   */
  @GetMapping("/{id}/operator-timeline")
  public OperatorFulfillmentTimelineResponse getOperatorTimeline(@PathVariable UUID id) {
    return readService.operatorTimeline(id);
  }

  /**
   * OP-CAP-23 — prefers an already-projected journey. When none exists yet, it requests a durable projection
   * (so the projector takes ownership) AND returns a read-materialized projection as the documented temporary
   * fallback, tagged {@code projectionSource=ON_READ_FALLBACK}. Backward compatible: always returns a detail.
   */
  @GetMapping("/by-source")
  public OrderJourneyDetailDto bySource(@RequestParam JourneySourceType sourceType, @RequestParam UUID sourceId) {
    return readService.detailBySourceIfPresent(sourceType, sourceId).orElseGet(() -> {
      UUID tenantId = TenantContext.requireTenantId();
      projectionPublisher.publishRefreshRequest(tenantId, sourceType, sourceId, "BY_SOURCE_READ");
      OrderJourney journey = journeyService.ensureJourney(sourceType, sourceId);
      return readService.detailByEntity(journey, "ON_READ_FALLBACK");
    });
  }

  @PostMapping("/{id}/signals")
  public OrderJourneyDetailDto recordSignal(@PathVariable UUID id,
      @RequestBody RecordFulfillmentSignalRequest request, HttpServletRequest http) {
    UUID actorId = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    OrderJourney journey = journeyService.recordSignal(id, request, actorId);
    return readService.detailByEntity(journey);
  }

  /** OP-CAP-46B — operator manually sets a fulfillment milestone. Audited, tenant-scoped, no external write. */
  @PostMapping("/{id}/manual-milestones")
  public OrderJourneyDetailDto recordManualMilestone(@PathVariable UUID id,
      @RequestBody RecordManualMilestoneRequest request, HttpServletRequest http) {
    UUID actorId = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    OrderJourney journey = journeyService.recordManualMilestone(id, request, actorId);
    return readService.detailByEntity(journey);
  }

  /**
   * OP-CAP-46C — operator mints a secure tracking link for this journey. Audited operator action
   * (REVIEW_ACTION via the order-journey prefix rule). Tenant from {@code X-Tenant-Id}, actor from the
   * trusted resolver, journey from the path; request body carries only an optional TTL. The raw token
   * is returned exactly once embedded in the path and is never stored or logged. No external write.
   */
  @PostMapping("/{id}/tracking-links")
  public TrackingLinkCreatedDto createTrackingLink(@PathVariable UUID id,
      @RequestBody(required = false) CreateTrackingLinkRequest request, HttpServletRequest http) {
    UUID actorId = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    return trackingLinkService.create(id, request, actorId);
  }

  /**
   * OP-CAP-46H — operator registry: list this journey's secure tracking links (newest first).
   * Protected operator read (ANALYTICS_READ via the GET order-journey prefix rule); tenant from
   * {@code X-Tenant-Id}, journey from the path. Each row carries only safe lifecycle metadata
   * (internal link id + timestamps + derived status) and never the raw token, its hash, the public
   * tracking path, the customer URL, the tenant id, or any actor id. Read-only; no external write.
   */
  @GetMapping("/{id}/tracking-links")
  public TrackingLinkListDto listTrackingLinks(@PathVariable UUID id) {
    return trackingLinkService.list(id);
  }

  /**
   * OP-CAP-46G — operator revokes a tracking link before its natural expiry. Audited operator action
   * (REVIEW_ACTION via the order-journey prefix rule). Tenant from {@code X-Tenant-Id}, actor from the
   * trusted resolver, journey + internal link id from the path; the request body carries only an
   * optional bounded reason and NEVER the raw token or any authority/state field. After revocation,
   * public resolution of the link's token is denied with the same generic not-found as an expired or
   * unknown link. No external write, no order-state/ETA/milestone mutation.
   */
  @PostMapping("/{id}/tracking-links/{linkId}/revoke")
  public TrackingLinkRevokedDto revokeTrackingLink(@PathVariable UUID id, @PathVariable UUID linkId,
      @RequestBody(required = false) RevokeTrackingLinkRequest request, HttpServletRequest http) {
    UUID actorId = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    return trackingLinkService.revoke(id, linkId, request, actorId);
  }

  /** OP-CAP-23 — explicit, tenant-scoped projector batch run (no background daemon). */
  @PostMapping("/projection/process")
  public ProcessJourneyProjectionResponse processProjection(@RequestParam(defaultValue = "50") int limit) {
    return projectorRunner.processTenantBatch(TenantContext.requireTenantId(), limit);
  }

  /**
   * OP-CAP-25 — bounded, tenant-scoped controlled drain for the CURRENT tenant only. Unlike the system
   * scheduled drain, this endpoint never crosses tenants: it resolves {@code X-Tenant-Id} and drains just
   * that tenant's clamped batch through the same projector runtime. REVIEW_ACTION (non-GET under this prefix
   * via {@code ApiPermissionInterceptor}); no external write; summary carries counts only.
   */
  @PostMapping("/projection/drain")
  public OrderJourneyProjectionDrainSummary drainProjection(
      @RequestParam(defaultValue = "25") int perTenantLimit) {
    return drainService.drainTenant(TenantContext.requireTenantId(), perTenantLimit);
  }

  /** OP-CAP-23 — explicit, audited, idempotent projection request for a known source. No external write. */
  @PostMapping("/projection-requests")
  public JourneyProjectionRequestResponse requestProjection(@RequestBody JourneyProjectionRequest request,
      HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID actorId = actorResolver.resolveVerifiedActor(http, tenantId);
    JourneySourceType sourceType = parseSourceType(request.sourceType());
    return projectorRunner.requestProjection(tenantId, sourceType, request.sourceId(),
        request.reasonCode(), actorId);
  }

  private static JourneySourceType parseSourceType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("sourceType is required");
    }
    try {
      return JourneySourceType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown journey source type: " + raw);
    }
  }
}
