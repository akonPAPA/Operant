package com.orderpilot.integration.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.orderpilot.security.production.DatabaseLeastPrivilegeValidator;
import com.orderpilot.support.LifecyclePostgresTestSupport;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof for separated migration/runtime database authority. */
@Testcontainers
@RequiresPostgresIntegration
class DatabaseLeastPrivilegePostgresIntegrationTest {
  private static final AtomicInteger SEQUENCE = new AtomicInteger();
  private static final String MIGRATOR_PASSWORD = "migrator-test-password-32";
  private static final String RUNTIME_PASSWORD = "runtime-test-password-32";

  @Test
  void restrictedRuntimeRolePassesValidatorAndCannotMutateProtectedBoundaries() {
    String suffix = Integer.toString(SEQUENCE.incrementAndGet());
    String schema = "lp_runtime_" + suffix;
    String migrator = "lp_migrator_" + suffix;
    String runtime = "lp_runtime_user_" + suffix;
    JdbcTemplate admin = adminJdbc();
    try {
      provision(admin, schema, migrator, runtime);
      JdbcTemplate migratorJdbc = jdbc(schema, migrator, MIGRATOR_PASSWORD);
      createProtectedTables(migratorJdbc);
      grantRuntimePrivileges(migratorJdbc, runtime);

      JdbcTemplate runtimeJdbc = jdbc(schema, runtime, RUNTIME_PASSWORD);
      assertThat(DatabaseLeastPrivilegeValidator.classify(
          DatabaseLeastPrivilegeValidator.inspect(runtimeJdbc), false)).isEmpty();

      runtimeJdbc.update("insert into lifecycle_operation (id, state) values (gen_random_uuid(), 'LEASED')");
      runtimeJdbc.update("insert into backup_artifact (id, state) values (gen_random_uuid(), 'STAGED')");
      runtimeJdbc.update("update backup_artifact set state = 'AVAILABLE' where state = 'STAGED'");
      runtimeJdbc.update("insert into lifecycle_operation_audit (event_type) values ('BACKUP_REQUESTED')");
      assertThat(runtimeJdbc.queryForObject("select count(*) from lifecycle_operation_audit", Integer.class))
          .isEqualTo(1);

      BoundarySnapshot before = snapshot(runtimeJdbc);
      assertDenied(runtimeJdbc, "update lifecycle_operation_audit set event_type = event_type");
      assertDenied(runtimeJdbc, "delete from lifecycle_operation_audit");
      assertDenied(runtimeJdbc, "truncate table lifecycle_operation_audit");
      assertDenied(runtimeJdbc, "alter table lifecycle_operation_audit add column denied integer");
      assertDenied(runtimeJdbc, "drop table lifecycle_operation_audit");
      assertDenied(runtimeJdbc, "delete from backup_artifact");
      assertDenied(runtimeJdbc, "truncate table backup_artifact");
      assertDenied(runtimeJdbc, "alter table backup_artifact add column denied integer");
      assertDenied(runtimeJdbc, "drop table backup_artifact");
      assertDenied(runtimeJdbc, "delete from lifecycle_operation");
      assertDenied(runtimeJdbc, "truncate table lifecycle_operation");
      assertDenied(runtimeJdbc, "create table runtime_flyway_forbidden (id integer)");
      assertThat(snapshot(runtimeJdbc)).isEqualTo(before);
    } finally {
      admin.execute("drop schema if exists " + ident(schema) + " cascade");
      admin.execute("drop role if exists " + ident(runtime));
      admin.execute("drop role if exists " + ident(migrator));
    }
  }

  @Test
  void validatorRejectsOwnerAndSuperuserAuthority() {
    String suffix = Integer.toString(SEQUENCE.incrementAndGet());
    String schema = "lp_owner_" + suffix;
    String migrator = "lp_owner_migrator_" + suffix;
    String runtime = "lp_owner_runtime_" + suffix;
    JdbcTemplate admin = adminJdbc();
    try {
      provision(admin, schema, migrator, runtime);
      JdbcTemplate migratorJdbc = jdbc(schema, migrator, MIGRATOR_PASSWORD);
      createProtectedTables(migratorJdbc);

      List<String> migratorReasons = DatabaseLeastPrivilegeValidator.classify(
          DatabaseLeastPrivilegeValidator.inspect(migratorJdbc), false);
      assertThat(migratorReasons).contains(
          DatabaseLeastPrivilegeValidator.RUNTIME_DB_OWNS_SCHEMA,
          DatabaseLeastPrivilegeValidator.PROTECTED_TABLE_OWNED_BY_RUNTIME + ":lifecycle_operation_audit",
          DatabaseLeastPrivilegeValidator.AUDIT_UPDATE_GRANTED_TO_RUNTIME);

      List<String> adminReasons = DatabaseLeastPrivilegeValidator.classify(
          DatabaseLeastPrivilegeValidator.inspect(jdbc(schema, LifecyclePostgresTestSupport.username(),
              LifecyclePostgresTestSupport.password())), false);
      assertThat(adminReasons).contains(DatabaseLeastPrivilegeValidator.RUNTIME_DB_ROLE_SUPERUSER);
    } finally {
      admin.execute("drop schema if exists " + ident(schema) + " cascade");
      admin.execute("drop role if exists " + ident(runtime));
      admin.execute("drop role if exists " + ident(migrator));
    }
  }

  private static void provision(JdbcTemplate admin, String schema, String migrator, String runtime) {
    admin.execute("drop schema if exists " + ident(schema) + " cascade");
    admin.execute("drop role if exists " + ident(runtime));
    admin.execute("drop role if exists " + ident(migrator));
    admin.execute("create role " + ident(migrator)
        + " login nosuperuser nocreatedb nocreaterole nobypassrls password '" + MIGRATOR_PASSWORD + "'");
    admin.execute("create role " + ident(runtime)
        + " login nosuperuser nocreatedb nocreaterole nobypassrls password '" + RUNTIME_PASSWORD + "'");
    admin.execute("create schema " + ident(schema) + " authorization " + ident(migrator));
    admin.execute("grant usage on schema " + ident(schema) + " to " + ident(runtime));
  }

  private static void createProtectedTables(JdbcTemplate migratorJdbc) {
    migratorJdbc.execute("create table lifecycle_operation (id uuid primary key, state text not null)");
    migratorJdbc.execute("create table backup_artifact (id uuid primary key, state text not null)");
    migratorJdbc.execute("create table lifecycle_operation_audit ("
        + "id bigint generated by default as identity primary key, event_type text not null)");
  }

  private static void grantRuntimePrivileges(JdbcTemplate migratorJdbc, String runtime) {
    migratorJdbc.execute("grant select, insert, update on lifecycle_operation to " + ident(runtime));
    migratorJdbc.execute("grant select, insert, update on backup_artifact to " + ident(runtime));
    migratorJdbc.execute("grant select, insert on lifecycle_operation_audit to " + ident(runtime));
    migratorJdbc.execute("grant usage, select on sequence lifecycle_operation_audit_id_seq to " + ident(runtime));
    migratorJdbc.execute("revoke update, delete, truncate on lifecycle_operation_audit from " + ident(runtime));
    migratorJdbc.execute("revoke delete, truncate on backup_artifact from " + ident(runtime));
    migratorJdbc.execute("revoke delete, truncate on lifecycle_operation from " + ident(runtime));
  }

  private static BoundarySnapshot snapshot(JdbcTemplate jdbcTemplate) {
    return new BoundarySnapshot(
        jdbcTemplate.queryForList("select * from lifecycle_operation order by id"),
        jdbcTemplate.queryForList("select * from backup_artifact order by id"),
        jdbcTemplate.queryForList("select * from lifecycle_operation_audit order by id"));
  }

  private static void assertDenied(JdbcTemplate jdbcTemplate, String sql) {
    Throwable thrown = catchThrowable(() -> jdbcTemplate.execute(sql));
    assertThat(thrown).isInstanceOf(DataAccessException.class);
    assertThat(deepestSqlState(thrown)).isEqualTo("42501");
  }

  private static String deepestSqlState(Throwable thrown) {
    String sqlState = null;
    Throwable current = thrown;
    while (current != null) {
      if (current instanceof SQLException sqlException) {
        sqlState = sqlException.getSQLState();
      }
      current = current.getCause();
    }
    return sqlState;
  }

  private static JdbcTemplate adminJdbc() {
    return jdbc(null, LifecyclePostgresTestSupport.username(), LifecyclePostgresTestSupport.password());
  }

  private static JdbcTemplate jdbc(String schema, String username, String password) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(withSchema(LifecyclePostgresTestSupport.jdbcUrl(), schema));
    dataSource.setUsername(username);
    dataSource.setPassword(password);
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.setQueryTimeout(5);
    return jdbcTemplate;
  }

  private static String withSchema(String jdbcUrl, String schema) {
    if (schema == null || schema.isBlank()) {
      return jdbcUrl;
    }
    return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
  }

  private static String ident(String value) {
    if (!value.matches("[a-z_][a-z0-9_]{0,62}")) {
      throw new IllegalArgumentException("unsafe identifier");
    }
    return '"' + value + '"';
  }

  private record BoundarySnapshot(
      List<Map<String, Object>> lifecycleOperations,
      List<Map<String, Object>> backupArtifacts,
      List<Map<String, Object>> auditEvents) {}
}
