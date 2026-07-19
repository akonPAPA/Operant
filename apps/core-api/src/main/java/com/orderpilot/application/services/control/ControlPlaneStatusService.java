package com.orderpilot.application.services.control;

import com.orderpilot.api.dto.ControlInternalDtos.ControlDiagnosticsResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlHealthResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlReadinessResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlStatusResponse;
import com.orderpilot.api.dto.ControlInternalDtos.DatabaseDiagnostics;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyState;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyStatus;
import com.orderpilot.api.dto.ControlInternalDtos.JvmDiagnostics;
import com.orderpilot.api.dto.ControlInternalDtos.RedisDiagnostics;
import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * P1-E - bounded platform control-plane status truth for the {@code /api/v1/internal/control/**}
 * surface. Every probe maps a dependency to a fixed logical name and a fixed {@link DependencyState}
 * token; raw failure detail (messages, hosts, SQL state) never leaves this service, so a control
 * response can never disclose topology, configuration values, or secret material.
 *
 * <p>Runtime bounds:
 * <ul>
 *   <li>WP-2: every operation ({@link #status()}, {@link #readiness()}, {@link #diagnostics()})
 *       creates exactly one absolute monotonic deadline at the start. Database and Redis probes run
 *       concurrently under that single shared budget, and the diagnostics migration lookup only runs
 *       when the database is UP and budget remains - no sub-operation ever resets or extends the
 *       deadline, so total runtime stays below the CLI operation timeout.</li>
 *   <li>WP-3: a fixed-size bulkhead ({@link #MAX_LIVE_PROBES} permits, no queue) caps the number of
 *       simultaneously executing probes. A non-interruptible probe (real JDBC/Redis work that ignores
 *       {@link Future#cancel(boolean)}) holds its permit until it truly finishes, so repeated
 *       timed-out requests cannot spawn unbounded work. Saturation fails closed to DOWN and never
 *       reports {@code ready=true}.</li>
 * </ul>
 *
 * <p>Readiness truth: the platform is ready only when PostgreSQL is reachable and, when a Redis
 * replay store is configured, Redis is reachable too. An unconfigured Redis is NOT_CONFIGURED and
 * does not fail readiness - readiness reflects required dependencies only.
 */
@Service
public class ControlPlaneStatusService {
  static final String DEPENDENCY_DATABASE = "database";
  static final String DEPENDENCY_REDIS = "redis";
  static final String DEPENDENCY_MIGRATION = "database-migration";
  static final int MAX_LIVE_PROBES = 4;
  static final Duration DEFAULT_DEPENDENCY_PROBE_DEADLINE = Duration.ofSeconds(6);
  private static final int PROBE_QUERY_TIMEOUT_SECONDS = 5;
  private static final String UNKNOWN_VERSION = "unknown";
  private static final System.Logger LOG = System.getLogger(ControlPlaneStatusService.class.getName());

  private final JdbcTemplate probeJdbcTemplate;
  private final ObjectProvider<StringRedisTemplate> replayRedisTemplate;
  private final Environment environment;
  private final Duration dependencyProbeDeadline;
  private final ExecutorService probeExecutor;
  private final Semaphore probePermits;
  private final ProbeObserver probeObserver;
  private final LongSupplier nanoClock;

  // Explicit @Autowired is required: the package-private test constructor below would otherwise
  // disable Spring Boot's single-constructor autowire fallback and fail context startup.
  @Autowired
  public ControlPlaneStatusService(
      DataSource dataSource,
      @Qualifier("gatewayHeaderReplayRedisTemplate") ObjectProvider<StringRedisTemplate> replayRedisTemplate,
      Environment environment) {
    this(dataSource, replayRedisTemplate, environment, DEFAULT_DEPENDENCY_PROBE_DEADLINE, LoggingProbeObserver.INSTANCE);
  }

  ControlPlaneStatusService(
      DataSource dataSource,
      ObjectProvider<StringRedisTemplate> replayRedisTemplate,
      Environment environment,
      Duration dependencyProbeDeadline,
      ProbeObserver probeObserver) {
    this(dataSource, replayRedisTemplate, environment, dependencyProbeDeadline, probeObserver, System::nanoTime);
  }

  ControlPlaneStatusService(
      DataSource dataSource,
      ObjectProvider<StringRedisTemplate> replayRedisTemplate,
      Environment environment,
      Duration dependencyProbeDeadline,
      ProbeObserver probeObserver,
      LongSupplier nanoClock) {
    if (dependencyProbeDeadline == null || dependencyProbeDeadline.isZero() || dependencyProbeDeadline.isNegative()) {
      throw new IllegalArgumentException("dependency probe deadline must be positive");
    }
    // Dedicated probe template with a bounded SQL query timeout. Connection acquisition is bounded
    // natively by the HikariCP connection-timeout and, defensively, by the aggregate deadline below.
    this.probeJdbcTemplate = new JdbcTemplate(dataSource);
    this.probeJdbcTemplate.setQueryTimeout(PROBE_QUERY_TIMEOUT_SECONDS);
    this.replayRedisTemplate = replayRedisTemplate;
    this.environment = environment;
    this.dependencyProbeDeadline = dependencyProbeDeadline;
    this.probeExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("control-plane-dependency-probe-", 0).factory());
    // Non-blocking, zero-queue bulkhead: at most MAX_LIVE_PROBES probes execute at once; further
    // probes fail closed instead of queueing, so non-interruptible work cannot accumulate unbounded.
    this.probePermits = new Semaphore(MAX_LIVE_PROBES);
    this.probeObserver = probeObserver == null ? LoggingProbeObserver.INSTANCE : probeObserver;
    this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
  }

  @PreDestroy
  void shutdownProbeExecutor() {
    probeExecutor.shutdownNow();
  }

  public ControlStatusResponse status() {
    return new ControlStatusResponse(version(), uptimeSeconds(), dependencyStatuses());
  }

  public ControlHealthResponse health() {
    // Liveness: reaching this code at all proves the process is up and serving requests.
    return new ControlHealthResponse("UP");
  }

  public ControlReadinessResponse readiness() {
    List<DependencyStatus> dependencies = dependencyStatuses();
    boolean ready = dependencies.stream()
        .allMatch(dependency -> dependency.state() != DependencyState.DOWN);
    return new ControlReadinessResponse(ready, dependencies);
  }

  public ControlDiagnosticsResponse diagnostics() {
    // WP-2: one absolute budget for the whole diagnostics call. Database and Redis probe concurrently;
    // the migration lookup only runs if the database is UP and budget remains, using the same budget.
    long deadlineNanos = nanoClock.getAsLong() + dependencyProbeDeadline.toNanos();
    ProbeOutcome<DependencyState> database = submitProbe(DEPENDENCY_DATABASE, this::databaseStateDirect);
    ProbeOutcome<DependencyState> redis = submitProbe(DEPENDENCY_REDIS, this::redisStateDirect);
    DependencyState databaseState = awaitState(DEPENDENCY_DATABASE, database, deadlineNanos);
    DependencyState redisState = awaitState(DEPENDENCY_REDIS, redis, deadlineNanos);
    return new ControlDiagnosticsResponse(
        version(),
        List.of(environment.getActiveProfiles()),
        new DatabaseDiagnostics(databaseState, migrationVersionWithinBudget(databaseState, deadlineNanos)),
        new RedisDiagnostics(redisState != DependencyState.NOT_CONFIGURED, redisState),
        jvmDiagnostics());
  }

  private List<DependencyStatus> dependencyStatuses() {
    long deadlineNanos = nanoClock.getAsLong() + dependencyProbeDeadline.toNanos();
    // Submit both before awaiting either, so the two probes overlap under one shared deadline.
    ProbeOutcome<DependencyState> database = submitProbe(DEPENDENCY_DATABASE, this::databaseStateDirect);
    ProbeOutcome<DependencyState> redis = submitProbe(DEPENDENCY_REDIS, this::redisStateDirect);
    return List.of(
        new DependencyStatus(DEPENDENCY_DATABASE, awaitState(DEPENDENCY_DATABASE, database, deadlineNanos)),
        new DependencyStatus(DEPENDENCY_REDIS, awaitState(DEPENDENCY_REDIS, redis, deadlineNanos)));
  }

  private String migrationVersionWithinBudget(DependencyState databaseState, long deadlineNanos) {
    if (databaseState != DependencyState.UP) {
      // Skip the migration query entirely when the database is not known to be UP.
      return UNKNOWN_VERSION;
    }
    if (deadlineNanos - nanoClock.getAsLong() <= 0L) {
      // No budget remains: do not submit additional work; record a bounded observability signal.
      probeObserver.timedOut(DEPENDENCY_MIGRATION, dependencyProbeDeadline);
      return UNKNOWN_VERSION;
    }
    ProbeOutcome<String> migration = submitProbe(DEPENDENCY_MIGRATION, this::migrationVersionDirect);
    if (migration.saturated()) {
      return UNKNOWN_VERSION;
    }
    return awaitValue(DEPENDENCY_MIGRATION, migration.future(), deadlineNanos, UNKNOWN_VERSION);
  }

  private DependencyState databaseStateDirect() {
    try {
      Integer probe = probeJdbcTemplate.queryForObject("SELECT 1", Integer.class);
      return probe != null && probe == 1 ? DependencyState.UP : DependencyState.DOWN;
    } catch (RuntimeException probeFailure) {
      // Failure detail stays server-side; the response vocabulary is the fixed state token only.
      return DependencyState.DOWN;
    }
  }

  private DependencyState redisStateDirect() {
    StringRedisTemplate template = replayRedisTemplate.getIfAvailable();
    if (template == null) {
      return DependencyState.NOT_CONFIGURED;
    }
    try {
      String pong = template.execute(
          (org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping());
      return "PONG".equalsIgnoreCase(pong) ? DependencyState.UP : DependencyState.DOWN;
    } catch (RuntimeException probeFailure) {
      return DependencyState.DOWN;
    }
  }

  private String migrationVersionDirect() {
    try {
      String version = probeJdbcTemplate.queryForObject(
          "SELECT version FROM flyway_schema_history WHERE success = TRUE"
              + " ORDER BY installed_rank DESC LIMIT 1",
          String.class);
      return version == null || version.isBlank() ? UNKNOWN_VERSION : version;
    } catch (RuntimeException probeFailure) {
      // No Flyway history (e.g. schema-managed test database) - bounded fallback, never an error leak.
      return UNKNOWN_VERSION;
    }
  }

  private <T> ProbeOutcome<T> submitProbe(String name, Callable<T> work) {
    if (!probePermits.tryAcquire()) {
      // Bulkhead saturated: fail closed without blocking and without enqueueing.
      probeObserver.saturated(name);
      return ProbeOutcome.saturatedOutcome();
    }
    try {
      Future<T> future = probeExecutor.submit(() -> {
        try {
          return work.call();
        } finally {
          // Released only when the underlying work truly finishes - a non-interruptible probe keeps
          // its permit, which is exactly what bounds accumulation of stuck work.
          probePermits.release();
        }
      });
      return ProbeOutcome.of(future);
    } catch (RejectedExecutionException shuttingDown) {
      probePermits.release();
      probeObserver.saturated(name);
      return ProbeOutcome.saturatedOutcome();
    }
  }

  private DependencyState awaitState(String name, ProbeOutcome<DependencyState> outcome, long deadlineNanos) {
    return outcome.saturated()
        ? DependencyState.DOWN
        : awaitValue(name, outcome.future(), deadlineNanos, DependencyState.DOWN);
  }

  private <T> T awaitValue(String name, Future<T> future, long deadlineNanos, T timeoutFallback) {
    long remainingNanos = deadlineNanos - nanoClock.getAsLong();
    try {
      if (remainingNanos <= 0L) {
        // The shared budget is spent. Honor a probe that already finished within the concurrent
        // window (e.g. a fast Redis probe not starved by a slow database probe), but never WAIT past
        // the deadline: a probe that has not finished is cancelled and its late result discarded.
        if (future.isDone()) {
          return future.get();
        }
        future.cancel(true);
        probeObserver.timedOut(name, dependencyProbeDeadline);
        return timeoutFallback;
      }
      return future.get(remainingNanos, TimeUnit.NANOSECONDS);
    } catch (TimeoutException timeout) {
      future.cancel(true);
      probeObserver.timedOut(name, dependencyProbeDeadline);
      return timeoutFallback;
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      probeObserver.interrupted(name);
      return timeoutFallback;
    } catch (ExecutionException failed) {
      probeObserver.failed(name, failed.getCause() == null ? "unknown" : failed.getCause().getClass().getSimpleName());
      return timeoutFallback;
    }
  }

  private static JvmDiagnostics jvmDiagnostics() {
    Runtime runtime = Runtime.getRuntime();
    long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    long maxMb = runtime.maxMemory() / (1024 * 1024);
    return new JvmDiagnostics(usedMb, maxMb);
  }

  private static long uptimeSeconds() {
    return ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
  }

  private String version() {
    String implementationVersion = ControlPlaneStatusService.class.getPackage().getImplementationVersion();
    return implementationVersion == null || implementationVersion.isBlank()
        ? UNKNOWN_VERSION
        : implementationVersion;
  }

  interface ProbeObserver {
    void timedOut(String dependency, Duration deadline);

    void interrupted(String dependency);

    void failed(String dependency, String failureType);

    void saturated(String dependency);
  }

  enum LoggingProbeObserver implements ProbeObserver {
    INSTANCE;

    @Override
    public void timedOut(String dependency, Duration deadline) {
      LOG.log(System.Logger.Level.WARNING,
          "control-plane dependency probe timed out dependency={0} deadlineMillis={1}",
          dependency, deadline.toMillis());
    }

    @Override
    public void interrupted(String dependency) {
      LOG.log(System.Logger.Level.WARNING,
          "control-plane dependency probe interrupted dependency={0}", dependency);
    }

    @Override
    public void failed(String dependency, String failureType) {
      LOG.log(System.Logger.Level.WARNING,
          "control-plane dependency probe failed dependency={0} failureType={1}", dependency, failureType);
    }

    @Override
    public void saturated(String dependency) {
      LOG.log(System.Logger.Level.WARNING,
          "control-plane dependency probe bulkhead saturated dependency={0}", dependency);
    }
  }

  private record ProbeOutcome<T>(Future<T> future, boolean saturated) {
    private static <T> ProbeOutcome<T> of(Future<T> future) {
      return new ProbeOutcome<>(future, false);
    }

    private static <T> ProbeOutcome<T> saturatedOutcome() {
      return new ProbeOutcome<>(null, true);
    }
  }
}
