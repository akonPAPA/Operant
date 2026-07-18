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
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
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
  private static final String UNKNOWN_VERSION = "unknown";

  private final JdbcTemplate probeJdbcTemplate;
  private final ObjectProvider<StringRedisTemplate> replayRedisTemplate;
  private final Environment environment;

  public ControlPlaneStatusService(
      DataSource dataSource,
      @Qualifier("gatewayHeaderReplayRedisTemplate") ObjectProvider<StringRedisTemplate> replayRedisTemplate,
      Environment environment) {
    // Dedicated probe template with a bounded query timeout so a wedged pool cannot hang the
    // control surface past the probe budget.
    this.probeJdbcTemplate = new JdbcTemplate(dataSource);
    this.probeJdbcTemplate.setQueryTimeout(PROBE_QUERY_TIMEOUT_SECONDS);
    this.replayRedisTemplate = replayRedisTemplate;
    this.environment = environment;
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
    List<DependencyStatus> dependencies = new ArrayList<>(2);
    dependencies.add(new DependencyStatus(DEPENDENCY_DATABASE, databaseState()));
    dependencies.add(new DependencyStatus(DEPENDENCY_REDIS, redisState()));
    return List.copyOf(dependencies);
  }

  private DependencyState databaseState() {
    try {
      Integer probe = probeJdbcTemplate.queryForObject("SELECT 1", Integer.class);
      return probe != null && probe == 1 ? DependencyState.UP : DependencyState.DOWN;
    } catch (RuntimeException probeFailure) {
      // Failure detail stays server-side; the response vocabulary is the fixed state token only.
      return DependencyState.DOWN;
    }
  }

  private DependencyState redisState() {
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
}
