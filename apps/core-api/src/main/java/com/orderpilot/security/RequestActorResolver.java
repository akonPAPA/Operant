package com.orderpilot.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
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
  private static final Set<String> LOCAL_DEMO_PROFILES = Set.of("local", "dev", "test", "demo");
  private static final Set<String> PRODUCTION_LIKE_PROFILES =
      Set.of("prod", "production", "cloud", "staging");

  public static final String ACTOR_HEADER = "X-OrderPilot-Actor-Id";
  public static final String SIGNATURE_HEADER = "X-OrderPilot-Actor-Signature";
  public static final String TIMESTAMP_HEADER = "X-OrderPilot-Actor-Timestamp";

  /** Stable sentinel recorded when no trusted actor header is supplied (unsigned fallback mode). */
  public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  /**
   * Backend-owned actor used only by {@link #resolveVerifiedLocalDemoOperator} for a headerless,
   * unsigned local/demo tenant-operator request. It is distinct from {@link #SYSTEM_ACTOR}.
   */
  public static final UUID LOCAL_DEMO_OPERATOR_ACTOR =
      UUID.fromString("00000000-0000-4000-8000-000000000002");

  private final SignedActorVerifier verifier;
  private final boolean localDemoRuntime;

  public RequestActorResolver(
      @Value("${orderpilot.security.actor-signing-secret:}") String signingSecret,
      @Value("${orderpilot.security.actor-signature-max-skew-seconds:300}") long maxSkewSeconds,
      Clock clock,
      Environment environment) {
    this.verifier = new SignedActorVerifier(signingSecret, maxSkewSeconds, clock);
    this.localDemoRuntime = isLocalDemoRuntime(environment);
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

  /**
   * Endpoint-scoped local/demo operator resolution for the RFQ demo decision flow.
   *
   * <p>A missing actor header becomes a backend-owned demo operator only when actor signing is not
   * configured and the runtime is explicitly local/dev/test/demo (or has no active profile, the
   * repository's documented local default). A supplied actor header is always resolved normally, so
   * an explicit {@link #SYSTEM_ACTOR} remains distinguishable and denyable. Signed mode still
   * rejects a missing actor before this fallback is considered.
   */
  public UUID resolveVerifiedLocalDemoOperator(HttpServletRequest request, UUID tenantId) {
    UUID actorId = resolveVerifiedActor(request, tenantId);
    if (!SYSTEM_ACTOR.equals(actorId)) {
      return actorId;
    }
    String suppliedActor = header(request, ACTOR_HEADER);
    if (suppliedActor != null && !suppliedActor.isBlank()) {
      return SYSTEM_ACTOR;
    }
    return !verifier.isConfigured() && localDemoRuntime
        ? LOCAL_DEMO_OPERATOR_ACTOR
        : SYSTEM_ACTOR;
  }

  private static boolean isLocalDemoRuntime(Environment environment) {
    if (environment == null) {
      return false;
    }
    String[] profiles = environment.getActiveProfiles();
    if (profiles.length == 0) {
      return true;
    }
    boolean localDemo = false;
    for (String profile : profiles) {
      String normalized = profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT);
      if (PRODUCTION_LIKE_PROFILES.contains(normalized)) {
        return false;
      }
      localDemo = localDemo || LOCAL_DEMO_PROFILES.contains(normalized);
    }
    return localDemo;
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
