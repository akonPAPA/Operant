package com.orderpilot.api.rest;

import com.orderpilot.api.dto.OrderJourneyDtos.PublicOrderTrackingView;
import com.orderpilot.application.services.journey.OrderJourneyTrackingLinkService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-46C — public, unauthenticated, READ-ONLY secure tracking link gateway.
 *
 * <p>The ONLY thing a client supplies is the opaque token in the path. There is no tenant header,
 * actor, customer id, journey id, status, confirmation flag, or any other authority input: the backend
 * resolves the entire (tenant, journey) scope from the verified token and returns a redacted
 * customer-safe tracking view. The route is classified PUBLIC (with token) by
 * {@code ApiRouteSecurityPolicy} and permit-listed for GET in {@code ApiSecurityWebConfig}; all other
 * order-journey routes remain permission-protected.
 *
 * <p>This endpoint performs no order-state, ETA, milestone, or external write — resolution only reads
 * and audits.
 */
@RestController
@RequestMapping("/api/v1/public/order-tracking")
public class OrderJourneyPublicTrackingController {
  private final OrderJourneyTrackingLinkService trackingLinkService;

  public OrderJourneyPublicTrackingController(OrderJourneyTrackingLinkService trackingLinkService) {
    this.trackingLinkService = trackingLinkService;
  }

  /** Resolve the secure tracking link. Scope is derived from the token only — never the request. */
  @GetMapping("/{token}")
  public PublicOrderTrackingView track(@PathVariable String token) {
    return trackingLinkService.resolvePublicTracking(token);
  }
}
