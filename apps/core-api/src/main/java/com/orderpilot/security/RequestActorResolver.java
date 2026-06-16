package com.orderpilot.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OP-CAP-16J/16K — resolves the audit actor for admin/governance HTTP mutations from the <b>trusted
 * request context</b>, never from the request body.
 *
 * <p>This service has no Spring Security principal; trust is carried by gateway-set headers (the same
 * trust boundary as {@code X-Tenant-Id} and {@code X-OrderPilot-Permissions}). The actor is taken
 * from {@code X-OrderPilot-Actor-Id}. A request can therefore not spoof the audit actor through a body
 * field.
 *
 * <p>OP-CAP-16K adds signed-actor verification on top of the trusted header:
 *
 * <ul>
 *   <li><b>Signing secret configured</b> ({@code orderpilot.security.actor-signing-secret}): a
 *       mutation must present a valid {@code X-OrderPilot-Actor-Signature} +
 *       {@code X-OrderPilot-Actor-Timestamp} over {@code tenantId\nactorId\ntimestamp}; otherwise the
 *       request is rejected (401). A missing actor header in signed mode is also a 401.
 *   <li><b>No signing secret</b> (local/dev/test): the 16J trusted-header fallback is preserved —
 *       actor header present → that actor; absent → {@link #SYSTEM_ACTOR}.
 * </ul>
 *
 * <p>In both modes a present-but-malformed actor UUID is a 400, and the body {@code actorId} is
 * always ignored.
 */
@Component
public class RequestActorResolver {
  public static final String ACTOR_HEADER = "X-OrderPilot-Actor-Id";
  public static final String SIGNATURE_HEADER = "X-OrderPilot-Actor-Signature";
  public static final String TIMESTAMP_HEADER = "X-OrderPilot-Actor-Timestamp";

  /** Stable sentinel recorded when no trusted actor header is supplied (unsigned fallback mode). */
  public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  private final SignedActorVerifier verifier;

  public RequestActorResolver(
      @Value("${orderpilot.security.actor-signing-secret:}") String signingSecret,
      @Value("${orderpilot.security.actor-signature-max-skew-seconds:300}") long maxSkewSeconds,
      Clock clock) {
    this.verifier = new SignedActorVerifier(signingSecret, maxSkewSeconds, clock);
  }

  /**
   * Resolve and (when a signing secret is configured) verify the actor for a mutation against the
   * current {@code tenantId}. The body actor is never consulted.
   */
  public UUID resolveVerifiedActor(HttpServletRequest request, UUID tenantId) {
    UUID actorId = parseActorId(request);
    if (verifier.isConfigured()) {
      if (actorId == null) {
        throw new ActorVerificationException("Actor identity is required");
      }
      verifier.verify(
          tenantId,
          actorId,
          header(request, SIGNATURE_HEADER),
          header(request, TIMESTAMP_HEADER));
      return actorId;
    }
    // Unsigned local/dev/test fallback (16J behavior preserved).
    return actorId == null ? SYSTEM_ACTOR : actorId;
  }

  private static UUID parseActorId(HttpServletRequest request) {
    String header = header(request, ACTOR_HEADER);
    if (header == null || header.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(header.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid " + ACTOR_HEADER + " header");
    }
  }

  private static String header(HttpServletRequest request, String name) {
    return request == null ? null : request.getHeader(name);
  }
}
