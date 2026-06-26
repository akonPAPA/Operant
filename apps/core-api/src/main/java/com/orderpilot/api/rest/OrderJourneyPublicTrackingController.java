package com.orderpilot.api.rest;

import com.orderpilot.api.dto.OrderJourneyDtos.PublicOrderTrackingView;
import com.orderpilot.application.services.journey.OrderJourneyTrackingLinkService;
import com.orderpilot.application.services.journey.PublicTrackingAbuseGuard;
import jakarta.servlet.http.HttpServletRequest;
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
 *
 * <p>Stage 9 abuse hardening: a per-client fixed-window {@link PublicTrackingAbuseGuard} is consulted
 * BEFORE the token is resolved, keyed on a hashed client identifier (never the token). This caps
 * high-volume brute-force/enumeration without requiring any operator auth, tenant header, or other
 * client-supplied authority, and without leaking whether the token exists — an over-limit client gets a
 * generic 429 regardless of token validity, while token-state denials remain the same generic 404.
 */
@RestController
@RequestMapping("/api/v1/public/order-tracking")
public class OrderJourneyPublicTrackingController {
  private final OrderJourneyTrackingLinkService trackingLinkService;
  private final PublicTrackingAbuseGuard abuseGuard;

  public OrderJourneyPublicTrackingController(OrderJourneyTrackingLinkService trackingLinkService,
      PublicTrackingAbuseGuard abuseGuard) {
    this.trackingLinkService = trackingLinkService;
    this.abuseGuard = abuseGuard;
  }

  /** Resolve the secure tracking link. Scope is derived from the token only — never the request. */
  @GetMapping("/{token}")
  public PublicOrderTrackingView track(@PathVariable String token, HttpServletRequest http) {
    // Abuse check first and token-independent: the guard sees only the hashed client address, never the
    // token, so a rate-limit denial cannot act as a token-existence oracle.
    abuseGuard.checkAndConsume(http.getRemoteAddr());
    return trackingLinkService.resolvePublicTracking(token);
  }
}
