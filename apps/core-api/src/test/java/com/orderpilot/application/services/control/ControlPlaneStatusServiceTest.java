package com.orderpilot.application.services.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import com.orderpilot.api.dto.ControlInternalDtos.ControlDiagnosticsResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlReadinessResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlStatusResponse;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyState;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * P1-E - behavioral truth and leak-contract proof for the bounded control-plane status service:
 * readiness reflects required dependencies only (an unconfigured Redis never fails readiness, a
 * configured-but-down Redis does), probe failures map to the fixed DOWN token without any raw error
 * text, and no serialized control response can disclose hosts, URLs, paths, configuration values,
 * credentials, or exception detail.
 */
class ControlPlaneStatusServiceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static DriverManagerDataSource h2() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl("jdbc:h2:mem:control-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    dataSource.setUsername("sa");
    return dataSource;
  }

  private static DriverManagerDataSource unreachableDatabase() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    // IFEXISTS on a database that does not exist -> every connection attempt fails.
    dataSource.setUrl("jdbc:h2:mem:missing-" + UUID.randomUUID() + ";IFEXISTS=TRUE");
    dataSource.setUsername("sa");
    return dataSource;
  }

  private static ObjectProvider<StringRedisTemplate> noRedis() {
    return provider(null);
  }

  private static ObjectProvider<StringRedisTemplate> provider(StringRedisTemplate template) {
    return new ObjectProvider<>() {
      @Override
      public StringRedisTemplate getObject() {
        if (template == null) {
          throw new IllegalStateException("not configured");
        }
        return template;
      }

      @Override
      public StringRedisTemplate getObject(Object... args) {
        return getObject();
      }

      @Override
      public StringRedisTemplate getIfAvailable() {
        return template;
      }

      @Override
      public StringRedisTemplate getIfUnique() {
        return template;
      }
    };
  }

  /** Fake Redis template answering ping without a real connection factory. */
  private static StringRedisTemplate redisAnswering(String pong, boolean fail) {
    return new StringRedisTemplate() {
      @Override
      @SuppressWarnings("unchecked")
      public <T> T execute(RedisCallback<T> action) {
        if (fail) {
          throw new InvalidDataAccessResourceUsageException(
              "connection refused to redis://secret-host:6379");
        }
        return (T) pong;
      }
    };
  }

  private static ControlPlaneStatusService service(
      DataSource dataSource, ObjectProvider<StringRedisTemplate> redis) {
    return service(dataSource, redis, Duration.ofSeconds(6), null);
  }

  private static ControlPlaneStatusService service(
      DataSource dataSource,
      ObjectProvider<StringRedisTemplate> redis,
      Duration deadline,
      ControlPlaneStatusService.ProbeObserver observer) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");
    return new ControlPlaneStatusService(dataSource, redis, environment, deadline, observer);
  }

  private static ControlPlaneStatusService service(
      DataSource dataSource,
      ObjectProvider<StringRedisTemplate> redis,
      Duration deadline,
      ControlPlaneStatusService.ProbeObserver observer,
      LongSupplier nanoClock) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");
    return new ControlPlaneStatusService(dataSource, redis, environment, deadline, observer, nanoClock);
  }

  /** Deterministic monotonic clock returning scripted nanos; the last value repeats when exhausted. */
  private static LongSupplier scriptedNanos(long... values) {
    AtomicInteger index = new AtomicInteger();
    return () -> {
      int at = index.getAndIncrement();
      return values[Math.min(at, values.length - 1)];
    };
  }

  @Test
  void readinessIsReadyWhenDatabaseUpAndRedisNotConfigured() {
    ControlReadinessResponse readiness = service(h2(), noRedis()).readiness();
    assertThat(readiness.ready()).isTrue();
    assertThat(readiness.dependencies())
        .containsExactly(
            new DependencyStatus("database", DependencyState.UP),
            new DependencyStatus("redis", DependencyState.NOT_CONFIGURED));
  }

  @Test
  void readinessFailsWhenDatabaseIsUnreachable() {
    ControlReadinessResponse readiness = service(unreachableDatabase(), noRedis()).readiness();
    assertThat(readiness.ready()).isFalse();
    assertThat(readiness.dependencies())
        .contains(new DependencyStatus("database", DependencyState.DOWN));
  }
  @Test
  void readinessTimesOutBlockedConnectionAcquisitionAndEmitsBoundedSignal() throws Exception {
    BlockingDataSource dataSource = new BlockingDataSource();
    RecordingProbeObserver observer = new RecordingProbeObserver();
    ControlPlaneStatusService statusService = service(dataSource, noRedis(), Duration.ofMillis(50), observer);
    try {
      long started = System.nanoTime();
      ControlReadinessResponse readiness = statusService.readiness();
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

      assertThat(readiness.ready()).isFalse();
      assertThat(readiness.dependencies())
          .containsExactly(
              new DependencyStatus("database", DependencyState.DOWN),
              new DependencyStatus("redis", DependencyState.NOT_CONFIGURED));
      assertThat(elapsedMillis).isLessThan(1_000L);
      assertThat(dataSource.entered.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(dataSource.interrupted.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(observer.events()).contains("timeout:database:50");
    } finally {
      statusService.shutdownProbeExecutor();
    }
  }

  @Test
  void readinessFailsWhenConfiguredRedisIsDown() {
    ControlReadinessResponse readiness =
        service(h2(), provider(redisAnswering(null, true))).readiness();
    assertThat(readiness.ready()).isFalse();
    assertThat(readiness.dependencies())
        .contains(new DependencyStatus("redis", DependencyState.DOWN));
  }

  @Test
  void readinessIsReadyWhenDatabaseAndConfiguredRedisAreUp() {
    ControlReadinessResponse readiness =
        service(h2(), provider(redisAnswering("PONG", false))).readiness();
    assertThat(readiness.ready()).isTrue();
    assertThat(readiness.dependencies())
        .containsExactly(
            new DependencyStatus("database", DependencyState.UP),
            new DependencyStatus("redis", DependencyState.UP));
  }

  @Test
  void healthIsAlwaysUpWhenServing() {
    assertThat(service(h2(), noRedis()).health().status()).isEqualTo("UP");
  }

  @Test
  void statusReportsBoundedVersionUptimeAndDependencies() {
    ControlStatusResponse status = service(h2(), noRedis()).status();
    assertThat(status.version()).isNotBlank();
    assertThat(status.uptimeSeconds()).isNotNegative();
    assertThat(status.dependencies()).hasSize(2);
  }

  @Test
  void diagnosticsMigrationVersionIsBoundedUnknownWithoutFlywayHistory() {
    ControlDiagnosticsResponse diagnostics = service(h2(), noRedis()).diagnostics();
    assertThat(diagnostics.database().state()).isEqualTo(DependencyState.UP);
    assertThat(diagnostics.database().migrationVersion()).isEqualTo("unknown");
    assertThat(diagnostics.redis().configured()).isFalse();
    assertThat(diagnostics.jvm().heapMaxMb()).isPositive();
  }

  @Test
  void diagnosticsReportsLatestSuccessfulFlywayVersion() {
    DriverManagerDataSource dataSource = h2();
    JdbcTemplate setup = new JdbcTemplate(dataSource);
    setup.execute(
        "CREATE TABLE flyway_schema_history ("
            + "installed_rank INT PRIMARY KEY, version VARCHAR(50), success BOOLEAN)");
    setup.update("INSERT INTO flyway_schema_history VALUES (1, '64', TRUE)");
    setup.update("INSERT INTO flyway_schema_history VALUES (2, '65', TRUE)");
    setup.update("INSERT INTO flyway_schema_history VALUES (3, '66', FALSE)");
    ControlDiagnosticsResponse diagnostics = service(dataSource, noRedis()).diagnostics();
    assertThat(diagnostics.database().migrationVersion()).isEqualTo("65");
  }

  @Test
  void noControlResponseLeaksTopologyConfigurationOrErrorDetail() throws Exception {
    // Force both failure paths so any leaked raw error text would be present if it could leak.
    ControlPlaneStatusService failing =
        service(unreachableDatabase(), provider(redisAnswering(null, true)));
    List<Object> responses = List.of(
        failing.status(), failing.health(), failing.readiness(), failing.diagnostics());
    for (Object response : responses) {
      String json = JSON.writeValueAsString(response).toLowerCase();
      for (String forbidden : List.of(
          "password", "secret", "token", "cookie", "signing",
          "host", "url", "jdbc", "6379", "5432", "127.0.0.1", "localhost",
          "exception", "stacktrace", "refused", "ifexists", "flyway_schema_history")) {
        assertThat(json).as("control response must not contain '%s'", forbidden)
            .doesNotContain(forbidden);
      }
    }
  }

  @Test
  void diagnosticsSkipsMigrationLookupWhenBudgetExhausted() {
    RecordingProbeObserver observer = new RecordingProbeObserver();
    // deadline=6s; scripted clock keeps DB/Redis awaits within budget but reports the budget spent by
    // the time the migration lookup is considered, so migration is skipped and never submitted.
    ControlDiagnosticsResponse diagnostics = service(
        h2(), noRedis(), Duration.ofSeconds(6), observer,
        scriptedNanos(0L, 0L, 0L, 7_000_000_000L)).diagnostics();

    assertThat(diagnostics.database().state()).isEqualTo(DependencyState.UP);
    assertThat(diagnostics.database().migrationVersion()).isEqualTo("unknown");
    assertThat(observer.events()).contains("timeout:database-migration:6000");
  }

  @Test
  void diagnosticsRunsMigrationLookupWhenBudgetRemains() {
    DriverManagerDataSource dataSource = h2();
    JdbcTemplate setup = new JdbcTemplate(dataSource);
    setup.execute(
        "CREATE TABLE flyway_schema_history ("
            + "installed_rank INT PRIMARY KEY, version VARCHAR(50), success BOOLEAN)");
    setup.update("INSERT INTO flyway_schema_history VALUES (1, '65', TRUE)");
    ControlDiagnosticsResponse diagnostics = service(
        dataSource, noRedis(), Duration.ofSeconds(6), null,
        scriptedNanos(0L, 0L, 0L, 0L, 0L)).diagnostics();

    assertThat(diagnostics.database().state()).isEqualTo(DependencyState.UP);
    assertThat(diagnostics.database().migrationVersion()).isEqualTo("65");
  }

  @Test
  void diagnosticsSkipsMigrationLookupWhenDatabaseDown() {
    ControlDiagnosticsResponse diagnostics = service(unreachableDatabase(), noRedis()).diagnostics();
    assertThat(diagnostics.database().state()).isEqualTo(DependencyState.DOWN);
    assertThat(diagnostics.database().migrationVersion()).isEqualTo("unknown");
  }

  @Test
  void unfinishedProbeIsCancelledToFallbackWhenSharedBudgetIsSpent() {
    RecordingProbeObserver observer = new RecordingProbeObserver();
    // A blocked (never-finishing) database probe past the shared deadline must yield the safe DOWN
    // fallback and be cancelled - its eventual late result is never adopted (WP-2 item 10).
    BlockingDataSource dataSource = new BlockingDataSource();
    ControlPlaneStatusService statusService =
        service(dataSource, noRedis(), Duration.ofMillis(50), observer);
    try {
      ControlReadinessResponse readiness = statusService.readiness();
      assertThat(readiness.dependencies())
          .contains(new DependencyStatus("database", DependencyState.DOWN));
      assertThat(readiness.ready()).isFalse();
      assertThat(observer.events()).contains("timeout:database:50");
    } finally {
      statusService.shutdownProbeExecutor();
    }
  }

  @Test
  void diagnosticsCompletesWithinOneAggregateDeadlineAndNeverReportsFalseHealthy() {
    long started = System.nanoTime();
    ControlDiagnosticsResponse diagnostics =
        service(new BlockingDataSource(), noRedis(), Duration.ofMillis(100), null).diagnostics();
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    // One aggregate budget governs DB + Redis + migration; a blocked database cannot stack deadlines.
    assertThat(elapsedMillis).isLessThan(2_000L);
    assertThat(diagnostics.database().state()).isEqualTo(DependencyState.DOWN);
    assertThat(diagnostics.database().migrationVersion()).isEqualTo("unknown");
  }

  @Test
  void aggregateDeadlineStaysBelowTheOperantctlDefaultTimeout() {
    // operantctl's default operation timeout is 10s; the server aggregate probe deadline must be lower
    // so the endpoint answers (even degraded) before the CLI gives up.
    assertThat(ControlPlaneStatusService.DEFAULT_DEPENDENCY_PROBE_DEADLINE.toMillis()).isLessThan(10_000L);
  }

  @Test
  void interruptedProbeWaitPreservesTheInterruptFlag() throws Exception {
    BlockingDataSource dataSource = new BlockingDataSource();
    ControlPlaneStatusService statusService =
        service(dataSource, noRedis(), Duration.ofSeconds(30), null);
    try {
      java.util.concurrent.atomic.AtomicBoolean interruptObserved =
          new java.util.concurrent.atomic.AtomicBoolean(false);
      Thread probe = new Thread(() -> {
        ControlReadinessResponse readiness = statusService.readiness();
        interruptObserved.set(Thread.currentThread().isInterrupted());
        assertThat(readiness.ready()).isFalse();
      });
      probe.start();
      assertThat(dataSource.entered.await(2, TimeUnit.SECONDS)).isTrue();
      probe.interrupt();
      probe.join(3_000);
      assertThat(interruptObserved.get()).isTrue();
    } finally {
      statusService.shutdownProbeExecutor();
    }
  }

  @Test
  void bulkheadCapsConcurrentNonInterruptibleProbesAndFailsClosed() throws Exception {
    HangingDataSource dataSource = new HangingDataSource();
    RecordingProbeObserver observer = new RecordingProbeObserver();
    ControlPlaneStatusService statusService =
        service(dataSource, noRedis(), Duration.ofMillis(100), observer);
    try {
      ControlReadinessResponse last = null;
      long postSaturationMillis = 0;
      for (int call = 0; call < 6; call++) {
        long started = System.nanoTime();
        last = statusService.readiness();
        postSaturationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      }
      // Non-interruptible probes hold their permits, so live DB connections never exceed the bulkhead.
      assertThat(dataSource.entered.get()).isLessThanOrEqualTo(ControlPlaneStatusService.MAX_LIVE_PROBES);
      assertThat(observer.events()).anyMatch(event -> event.startsWith("saturated:"));
      assertThat(last).isNotNull();
      assertThat(last.ready()).isFalse();
      // A request after saturation fails closed quickly rather than blocking on capacity.
      assertThat(postSaturationMillis).isLessThan(500L);
    } finally {
      dataSource.release();
      statusService.shutdownProbeExecutor();
    }
  }

  @Test
  void probeSubmissionAfterExecutorShutdownFailsClosedWithoutThrowing() {
    ControlPlaneStatusService statusService = service(h2(), noRedis(), Duration.ofSeconds(6), new RecordingProbeObserver());
    statusService.shutdownProbeExecutor();
    ControlReadinessResponse readiness = statusService.readiness();
    assertThat(readiness.ready()).isFalse();
    assertThat(readiness.dependencies())
        .contains(new DependencyStatus("database", DependencyState.DOWN));
  }

  private static final class HangingDataSource implements DataSource {
    private final AtomicInteger entered = new AtomicInteger();
    private final CountDownLatch gate = new CountDownLatch(1);

    void release() {
      gate.countDown();
    }

    @Override
    public Connection getConnection() throws SQLException {
      entered.incrementAndGet();
      // Ignore interruption to simulate a non-interruptible driver acquisition; hold the permit until
      // the test releases the gate.
      boolean interrupted = false;
      while (gate.getCount() > 0) {
        try {
          gate.await();
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      throw new SQLException("released");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }

  private static final class RecordingProbeObserver implements ControlPlaneStatusService.ProbeObserver {
    private final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();

    @Override
    public void timedOut(String dependency, Duration deadline) {
      events.add("timeout:" + dependency + ":" + deadline.toMillis());
    }

    @Override
    public void interrupted(String dependency) {
      events.add("interrupted:" + dependency);
    }

    @Override
    public void failed(String dependency, String failureType) {
      events.add("failed:" + dependency + ":" + failureType);
    }

    @Override
    public void saturated(String dependency) {
      events.add("saturated:" + dependency);
    }

    private java.util.List<String> events() {
      return events;
    }
  }

  private static final class BlockingDataSource implements DataSource {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);

    @Override
    public Connection getConnection() throws SQLException {
      entered.countDown();
      try {
        while (true) {
          Thread.sleep(1_000L);
        }
      } catch (InterruptedException cancellation) {
        interrupted.countDown();
        Thread.currentThread().interrupt();
        throw new SQLException("interrupted");
      }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
