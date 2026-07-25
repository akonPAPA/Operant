package com.orderpilot.support;

import java.util.Optional;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Test-only PostgreSQL bootstrap for lifecycle authority proofs.
 *
 * <p>Default: Testcontainers {@code postgres:16-alpine} when the Docker engine is reachable.
 * Explicit external mode: {@code ORDERPILOT_TEST_POSTGRES_JDBC_URL} /
 * {@code ORDERPILOT_TEST_POSTGRES_USERNAME} / {@code ORDERPILOT_TEST_POSTGRES_PASSWORD}.
 *
 * <p>Neither mode may silently become a skipped PASS; absence of both fails closed.
 */
public final class LifecyclePostgresTestSupport {
  public static final String IMAGE = "postgres:16-alpine";

  private static final Object LOCK = new Object();
  private static volatile Mode mode;
  private static volatile PostgreSQLContainer<?> container;

  private LifecyclePostgresTestSupport() {}

  public static void register(DynamicPropertyRegistry registry) {
    Mode active = ensureStarted();
    registry.add("spring.datasource.url", active::jdbcUrl);
    registry.add("spring.datasource.username", active::username);
    registry.add("spring.datasource.password", active::password);
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 12);
    registry.add("orderpilot.control.lifecycle.executor.enabled", () -> true);
    registry.add("orderpilot.test.postgres.mode", active::name);
    registry.add("orderpilot.test.postgres.image", () -> IMAGE);
  }

  public static String activeMode() {
    return ensureStarted().name();
  }

  public static String jdbcUrl() {
    return ensureStarted().jdbcUrl();
  }

  public static String username() {
    return ensureStarted().username();
  }

  public static String password() {
    return ensureStarted().password();
  }

  private static Mode ensureStarted() {
    Mode current = mode;
    if (current != null) {
      return current;
    }
    synchronized (LOCK) {
      if (mode != null) {
        return mode;
      }
      Optional<Mode> external = externalMode();
      if (external.isPresent()) {
        mode = external.get();
        return mode;
      }
      try {
        PostgreSQLContainer<?> started = new PostgreSQLContainer<>(IMAGE);
        started.start();
        container = started;
        mode = new Mode(
            "TESTCONTAINERS",
            started.getJdbcUrl(),
            started.getUsername(),
            started.getPassword());
        return mode;
      } catch (Throwable testcontainersUnavailable) {
        throw new IllegalStateException(
            "PostgreSQL integration required but Testcontainers could not reach Docker and "
                + "ORDERPILOT_TEST_POSTGRES_JDBC_URL is unset",
            testcontainersUnavailable);
      }
    }
  }

  private static Optional<Mode> externalMode() {
    String url = firstNonBlank(
        System.getenv("ORDERPILOT_TEST_POSTGRES_JDBC_URL"),
        System.getProperty("orderpilot.test.postgres.jdbc-url"));
    if (url == null) {
      return Optional.empty();
    }
    String user = required(
        firstNonBlank(
            System.getenv("ORDERPILOT_TEST_POSTGRES_USERNAME"),
            System.getProperty("orderpilot.test.postgres.username")),
        "ORDERPILOT_TEST_POSTGRES_USERNAME / orderpilot.test.postgres.username");
    String password = required(
        firstNonBlank(
            System.getenv("ORDERPILOT_TEST_POSTGRES_PASSWORD"),
            System.getProperty("orderpilot.test.postgres.password")),
        "ORDERPILOT_TEST_POSTGRES_PASSWORD / orderpilot.test.postgres.password");
    return Optional.of(new Mode("EXPLICIT_EXTERNAL", url, user, password));
  }

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    if (second != null && !second.isBlank()) {
      return second;
    }
    return null;
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required when ORDERPILOT_TEST_POSTGRES_JDBC_URL is set");
    }
    return value;
  }

  private record Mode(String name, String jdbcUrl, String username, String password) {}
}
