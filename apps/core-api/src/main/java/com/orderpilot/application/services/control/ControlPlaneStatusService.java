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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * <p>Readiness truth: the platform is ready only when PostgreSQL is reachable and, when a Redis
 * replay store is configured, Redis is reachable too. An unconfigured Redis is NOT_CONFIGURED and
 * does not fail readiness - readiness reflects required dependencies only.
 */
@Service
public class ControlPlaneStatusService {
  static final String DEPENDENCY_DATABASE = "database";
  static final String DEPENDENCY_REDIS = "redis";
  private static final int PROBE_QUERY_TIMEOUT_SECONDS = 5;
  private static final Duration DEFAULT_DEPENDENCY_PROBE_DEADLINE = Duration.ofSeconds(6);
  private static final String UNKNOWN_VERSION = "unknown";
  private static final System.Logger LOG = System.getLogger(ControlPlaneStatusService.class.getName());

  private final JdbcTemplate probeJdbcTemplate;
  private final ObjectProvider<StringRedisTemplate> replayRedisTemplate;
  private final Environment environment;
  private final Duration dependencyProbeDeadline;
  private final ExecutorService probeExecutor;
  private final ProbeObserver probeObserver;

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
    if (dependencyProbeDeadline == null || dependencyProbeDeadline.isZero() || dependencyProbeDeadline.isNegative()) {
      throw new IllegalArgumentException("dependency probe deadline must be positive");
    }
    // Dedicated probe template with a bounded SQL query timeout. Connection acquisition and Redis
    // calls are additionally wrapped by the aggregate dependency deadline below.
    this.probeJdbcTemplate = new JdbcTemplate(dataSource);
    this.probeJdbcTemplate.setQueryTimeout(PROBE_QUERY_TIMEOUT_SECONDS);
    this.replayRedisTemplate = replayRedisTemplate;
    this.environment = environment;
    this.dependencyProbeDeadline = dependencyProbeDeadline;
    this.probeExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("control-plane-dependency-probe-", 0).factory());
    this.probeObserver = probeObserver == null ? LoggingProbeObserver.INSTANCE : probeObserver;
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
    DependencyState databaseState = databaseState();
    return new ControlDiagnosticsResponse(
        version(),
        List.of(environment.getActiveProfiles()),
        new DatabaseDiagnostics(databaseState, migrationVersion(databaseState)),
        redisDiagnostics(),
        jvmDiagnostics());
  }

  private List<DependencyStatus> dependencyStatuses() {
    long deadlineNanos = System.nanoTime() + dependencyProbeDeadline.toNanos();
    List<ProbeFuture<DependencyState>> probes = new ArrayList<>(2);
    probes.add(new ProbeFuture<>(DEPENDENCY_DATABASE, probeExecutor.submit(this::databaseStateDirect)));
    probes.add(new ProbeFuture<>(DEPENDENCY_REDIS, probeExecutor.submit(this::redisStateDirect)));
    List<DependencyStatus> dependencies = new ArrayList<>(2);
    for (ProbeFuture<DependencyState> probe : probes) {
      dependencies.add(new DependencyStatus(probe.name(), awaitDependency(probe, deadlineNanos)));
    }
    return List.copyOf(dependencies);
  }

  private DependencyState databaseState() {
    return awaitDependency(
        new ProbeFuture<>(DEPENDENCY_DATABASE, probeExecutor.submit(this::databaseStateDirect)),
        System.nanoTime() + dependencyProbeDeadline.toNanos());
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

  private DependencyState redisState() {
    return awaitDependency(
        new ProbeFuture<>(DEPENDENCY_REDIS, probeExecutor.submit(this::redisStateDirect)),
        System.nanoTime() + dependencyProbeDeadline.toNanos());
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

  private RedisDiagnostics redisDiagnostics() {
    DependencyState state = redisState();
    return new RedisDiagnostics(state != DependencyState.NOT_CONFIGURED, state);
  }

  private String migrationVersion(DependencyState databaseState) {
    if (databaseState != DependencyState.UP) {
      return UNKNOWN_VERSION;
    }
    return awaitValue(
        "database-migration",
        probeExecutor.submit(this::migrationVersionDirect),
        System.nanoTime() + dependencyProbeDeadline.toNanos(),
        UNKNOWN_VERSION);
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

  private DependencyState awaitDependency(ProbeFuture<DependencyState> probe, long deadlineNanos) {
    return awaitValue(probe.name(), probe.future(), deadlineNanos, DependencyState.DOWN);
  }

  private <T> T awaitValue(String name, Future<T> future, long deadlineNanos, T timeoutFallback) {
    long remainingNanos = deadlineNanos - System.nanoTime();
    try {
      if (remainingNanos <= 0L) {
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
  }

  private record ProbeFuture<T>(String name, Future<T> future) {}
}
