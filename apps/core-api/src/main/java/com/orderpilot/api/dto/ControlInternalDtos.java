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
 *       tenant identifiers, customer data, or raw dependency error text. A dependency is described
 *       only by a fixed logical name and a fixed state token.</li>
 *   <li>Dependency failure detail stays server-side (logs); the response carries state only, so a
 *       leaked response can never disclose topology or secret material.</li>
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
   * Bounded, redacted platform diagnostics. Profile names are Spring profile identifiers (e.g.
   * {@code production}) - never configuration values.
   */
  public record ControlDiagnosticsResponse(
      String version,
      List<String> activeProfiles,
      DatabaseDiagnostics database,
      RedisDiagnostics redis,
      JvmDiagnostics jvm) {}

  /**
   * P1-E lifecycle (operational-event slice) - one bounded, server-owned, TYPED operational event.
   * This is NOT a log line: {@code eventCode} and {@code component} are closed server allowlists,
   * {@code severity} is a closed token, and {@code summary} is generated from a bounded server
   * template (never an arbitrary application/logger message). No tenant/actor/customer identifier,
   * payload, path, host, exception text, stack trace, credential, or free-form metadata is present.
   * {@code correlationId} is optional (may be {@code null}) and bounded. {@code occurredAt} is an
   * ISO-8601 UTC timestamp.
   */
  public record OperationalEventProjection(
      String occurredAt,
      String eventCode,
      String component,
      String severity,
      String summary,
      String correlationId) {}

  /**
   * Bounded, cursor-paginated page of recent operational events, newest first. {@code nextCursor} is
   * the opaque cursor (an event sequence) to pass back as {@code before} to fetch the next (older)
   * page, or {@code null} when no older events remain. {@code returned} equals {@code events.size()}
   * and never exceeds {@code maxLimit}.
   *
   * <p>Honest runtime scope: {@code scope} is always {@code LOCAL_PROCESS_RECENT_OPERATIONAL_EVENTS}.
   * This surface is a fixed-capacity, NON-DURABLE, process-local ring - it holds only this instance's
   * recent operational events, a restart clears it, and it provides NO multi-instance aggregation.
   * {@code instanceId} is an opaque per-process identifier (never a host, pid, or path).
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
