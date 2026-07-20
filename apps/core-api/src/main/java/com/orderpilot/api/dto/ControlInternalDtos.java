package com.orderpilot.api.dto;

import java.util.List;

/**
 * P1-E - response contracts for the bounded platform control-plane read surface under
 * {@code /api/v1/internal/control/**}, consumed by operantctl.
 *
 * <p>Contract law (enforced by the service, proven by {@code InternalControlContractTest}):
 * <ul>
 *   <li>This slice is read-only: there are no request DTOs and no mutation surface.</li>
 *   <li>Responses are platform-scoped and bounded. They never expose configuration values, hosts,
 *       ports, URLs, file paths, environment variables, credentials, tokens, cookies, signing keys,
 *       tenant identifiers, customer data, or raw dependency error text.</li>
 *   <li>Dependency failure detail stays server-side; the response carries fixed state tokens only.</li>
 * </ul>
 */
public final class ControlInternalDtos {
  private ControlInternalDtos() {}

  /** Fixed dependency state tokens - the only failure vocabulary a control response may use. */
  public enum DependencyState {
    UP,
    DOWN,
    NOT_CONFIGURED
  }

  /** One required or optional platform dependency, named logically (never by host/URL). */
  public record DependencyStatus(String name, DependencyState state) {}

  /** Bounded platform status: version, uptime, and logical dependency states. */
  public record ControlStatusResponse(
      String version,
      long uptimeSeconds,
      List<DependencyStatus> dependencies) {}

  /** Liveness: the process is up and serving. Always {@code UP} when reachable. */
  public record ControlHealthResponse(String status) {}

  /** Readiness truth: ready only when every required dependency is {@code UP}. */
  public record ControlReadinessResponse(boolean ready, List<DependencyStatus> dependencies) {}

  /** Bounded database diagnostics: state and applied migration version only. */
  public record DatabaseDiagnostics(DependencyState state, String migrationVersion) {}

  /** Bounded Redis diagnostics: whether a Redis replay store is configured and its state. */
  public record RedisDiagnostics(boolean configured, DependencyState state) {}

  /** Bounded JVM pressure facts - sizes only, no paths or flags. */
  public record JvmDiagnostics(long heapUsedMb, long heapMaxMb) {}

  /**
   * Bounded, redacted platform diagnostics. Profile names are Spring profile identifiers (for example
   * {@code production}) - never configuration values.
   */
  public record ControlDiagnosticsResponse(
      String version,
      List<String> activeProfiles,
      DatabaseDiagnostics database,
      RedisDiagnostics redis,
      JvmDiagnostics jvm) {}

  /**
   * One bounded, server-owned typed operational event. In this slice, dependency/readiness events are
   * changed observations sampled when authenticated status/readiness endpoints are polled; they are
   * not raw log lines or independent background incident detections. {@code summary} is generated from
   * a fixed template. No tenant/actor/customer identifier, payload, path, host, exception text, stack
   * trace, credential, or free-form metadata is present.
   */
  public record OperationalEventProjection(
      String occurredAt,
      String eventCode,
      String component,
      String severity,
      String summary,
      String correlationId) {}

  /**
   * Bounded newest-first page over the current process-local retained window. {@code nextCursor} is the
   * exclusive sequence boundary for an older page. The ring is non-durable and fixed-capacity: restart
   * clears it, instances do not aggregate, and events evicted between page requests cannot be recovered.
   * {@code instanceId} is an opaque per-process identifier that lets a client detect process changes.
   */
  public record OperationalEventPage(
      List<OperationalEventProjection> events,
      String nextCursor,
      boolean hasMore,
      int returned,
      int maxLimit,
      String scope,
      String instanceId) {}
}
