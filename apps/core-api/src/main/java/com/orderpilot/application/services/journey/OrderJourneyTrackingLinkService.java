package com.orderpilot.application.services.journey;

import com.orderpilot.api.dto.OrderJourneyDtos.CreateTrackingLinkRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.PublicOrderTrackingView;
import com.orderpilot.api.dto.OrderJourneyDtos.RevokeTrackingLinkRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkCreatedDto;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkRevokedDto;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.domain.journey.OrderJourneyRepository;
import com.orderpilot.domain.journey.OrderJourneyTrackingLink;
import com.orderpilot.domain.journey.OrderJourneyTrackingLinkRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-46C — Order Journey Secure Tracking Link foundation.
 *
 * <p>Mints and resolves a narrow, tokenized, tenant- and journey-scoped, expiring, READ-ONLY secure
 * tracking link that resolves to exactly one customer-safe order-journey tracking view. This is not a
 * buyer portal, login/session system, WMS/TMS, or carrier integration, and it performs no external
 * write and no order-state/ETA/milestone mutation.
 *
 * <p>Token handling:
 * <ul>
 *   <li>A high-entropy random token is generated and returned to the operator exactly once at creation
 *       (embedded in the shareable path). Only its SHA-256 hash is persisted; the raw token is never
 *       stored, never returned again, and never logged.</li>
 *   <li>Resolution is keyed on the token hash. The tenant/journey scope is read from the resolved row
 *       — never from the request — so a token minted for tenant/journey A can never reach tenant/journey
 *       B. The tenant-scoped re-fetch in the read service is the second, defence-in-depth check.</li>
 *   <li>Expiry is enforced deterministically; an expired or unknown token is denied with the same
 *       generic not-found result (no validity oracle).</li>
 * </ul>
 */
@Service
public class OrderJourneyTrackingLinkService {
  /** Public, unauthenticated resolve path; the raw token is the only handle the client supplies. */
  public static final String PUBLIC_PATH_PREFIX = "/api/v1/public/order-tracking/";

  private static final int DEFAULT_TTL_HOURS = 168;   // 7 days
  private static final int MIN_TTL_HOURS = 1;
  private static final int MAX_TTL_HOURS = 720;       // 30 days
  private static final int TOKEN_BYTES = 32;          // 256 bits of entropy

  private final OrderJourneyTrackingLinkRepository trackingLinkRepository;
  private final OrderJourneyRepository journeyRepository;
  private final OrderJourneyReadService readService;
  private final AuditEventService auditEventService;
  private final Clock clock;
  private final SecureRandom secureRandom = new SecureRandom();

  public OrderJourneyTrackingLinkService(OrderJourneyTrackingLinkRepository trackingLinkRepository,
      OrderJourneyRepository journeyRepository, OrderJourneyReadService readService,
      AuditEventService auditEventService, Clock clock) {
    this.trackingLinkRepository = trackingLinkRepository;
    this.journeyRepository = journeyRepository;
    this.readService = readService;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  /**
   * Operator action: mint a secure tracking link for one of the current tenant's journeys. Tenant is
   * resolved from {@link TenantContext} (header), the journey from the path id, and the actor from the
   * trusted resolver — never from the request body. Audited. No external write.
   */
  @Transactional
  public TrackingLinkCreatedDto create(UUID journeyId, CreateTrackingLinkRequest request, UUID actorId) {
    UUID tenantId = TenantContext.requireTenantId();
    Instant now = clock.instant();
    OrderJourney journey = journeyRepository.findByIdAndTenantId(journeyId, tenantId)
        .orElseThrow(() -> new NotFoundException("Order journey not found"));

    int ttlHours = clampTtl(request == null ? null : request.expiresInHours());
    Instant expiresAt = now.plus(Duration.ofHours(ttlHours));
    String rawToken = generateToken();

    trackingLinkRepository.save(new OrderJourneyTrackingLink(tenantId, journey.getId(), sha256(rawToken),
        expiresAt, actorId, now));

    // Audit carries ids + expiry only — never the raw token or its hash.
    auditEventService.record("ORDER_JOURNEY_TRACKING_LINK_CREATED", "ORDER_JOURNEY", journey.getId().toString(),
        actorId, "{\"expiresAt\":\"" + expiresAt + "\"}");

    return new TrackingLinkCreatedDto(PUBLIC_PATH_PREFIX + rawToken, expiresAt);
  }

  /**
   * OP-CAP-46G — operator action: revoke a tracking link before its natural expiry. The link is
   * identified by its internal id within the operator's tenant AND the path journey — never by the raw
   * token (which the operator does not hold and must never need). Tenant comes from {@link
   * TenantContext} (header), journey from the path id, actor from the trusted resolver.
   *
   * <p>Scope isolation: a cross-tenant or cross-journey link id resolves to nothing and is denied with
   * a generic not-found. An already-revoked link is a stable idempotent no-op (first-write-wins on
   * actor/time/reason, no duplicate audit event). An expired link may still be revoked by id; either
   * way public access stays denied. Revocation mutates only this link row — never the journey,
   * milestones, signals, or ETA — and is audited with ids + revoked time only (no token, no hash).
   */
  @Transactional
  public TrackingLinkRevokedDto revoke(UUID journeyId, UUID linkId, RevokeTrackingLinkRequest request,
      UUID actorId) {
    UUID tenantId = TenantContext.requireTenantId();
    Instant now = clock.instant();
    OrderJourneyTrackingLink link = trackingLinkRepository
        .findByIdAndTenantIdAndJourneyId(linkId, tenantId, journeyId)
        .orElseThrow(() -> new NotFoundException("Tracking link not found"));

    if (!link.isRevoked()) {
      link.revoke(actorId, sanitizeReason(request == null ? null : request.reason()), now);
      trackingLinkRepository.save(link);
      // Audit carries ids + revoked time only — never the raw token, its hash, or the reason text.
      auditEventService.record("ORDER_JOURNEY_TRACKING_LINK_REVOKED", "ORDER_JOURNEY",
          journeyId.toString(), actorId,
          "{\"linkId\":\"" + link.getId() + "\",\"revokedAt\":\"" + link.getRevokedAt() + "\"}");
    }
    return new TrackingLinkRevokedDto("REVOKED", link.getRevokedAt());
  }

  /**
   * Public resolution: verify the token, derive the (tenant, journey) scope from the stored row, and
   * return the redacted customer-safe tracking view. Read-only — no journey/milestone/signal/ETA
   * mutation. An invalid or expired token is denied identically (generic not-found). The successful
   * access is audited.
   */
  @Transactional(readOnly = true)
  public PublicOrderTrackingView resolvePublicTracking(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      throw new NotFoundException("Tracking link not found or no longer available");
    }
    Instant now = clock.instant();
    // A revoked link (OP-CAP-46G) is denied identically to an expired/unknown one — no validity oracle.
    OrderJourneyTrackingLink link = trackingLinkRepository.findByTokenHash(sha256(rawToken))
        .filter(candidate -> !candidate.isExpired(now) && !candidate.isRevoked())
        .orElseThrow(() -> new NotFoundException("Tracking link not found or no longer available"));

    // Trust boundary: scope comes from the verified token row. Set the resolved tenant so the
    // tenant-scoped read and the audit event are bound to the token's tenant, never the request.
    TenantContext.setTenantId(link.getTenantId());
    PublicOrderTrackingView view = readService.publicTracking(link.getTenantId(), link.getJourneyId());

    auditEventService.record("ORDER_JOURNEY_TRACKING_LINK_ACCESSED", "ORDER_JOURNEY",
        link.getJourneyId().toString(), null, "{}");
    return view;
  }

  /**
   * OP-CAP-46G — bound and clean the optional operator-only revocation reason: trim, collapse control
   * characters (defence against log/storage injection), clamp to the column width, and treat blank as
   * absent (null). Operator-only — it is never returned to the customer or echoed in the response.
   */
  private static String sanitizeReason(String reason) {
    if (reason == null) {
      return null;
    }
    String cleaned = reason.replaceAll("[\\p{Cntrl}]", " ").trim();
    if (cleaned.isEmpty()) {
      return null;
    }
    return cleaned.length() > OrderJourneyTrackingLink.MAX_REVOCATION_REASON_LENGTH
        ? cleaned.substring(0, OrderJourneyTrackingLink.MAX_REVOCATION_REASON_LENGTH)
        : cleaned;
  }

  private static int clampTtl(Integer requestedHours) {
    if (requestedHours == null || requestedHours <= 0) {
      return DEFAULT_TTL_HOURS;
    }
    return Math.min(Math.max(requestedHours, MIN_TTL_HOURS), MAX_TTL_HOURS);
  }

  private String generateToken() {
    byte[] raw = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required for tracking link token hashing", ex);
    }
  }
}
