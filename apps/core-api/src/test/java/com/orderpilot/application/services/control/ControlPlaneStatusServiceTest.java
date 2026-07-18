package com.orderpilot.application.services.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.ControlInternalDtos.ControlDiagnosticsResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlReadinessResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlStatusResponse;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyState;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * P1-E — behavioral truth and leak-contract proof for the bounded control-plane status service:
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
      javax.sql.DataSource dataSource, ObjectProvider<StringRedisTemplate> redis) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");
    return new ControlPlaneStatusService(dataSource, redis, environment);
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
}
