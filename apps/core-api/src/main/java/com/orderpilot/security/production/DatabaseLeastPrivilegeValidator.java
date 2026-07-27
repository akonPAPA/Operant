package com.orderpilot.security.production;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Classifies PostgreSQL runtime database authority for production startup checks. */
public final class DatabaseLeastPrivilegeValidator {

  public static final String RUNTIME_FLYWAY_ENABLED = "RUNTIME_FLYWAY_ENABLED";
  public static final String RUNTIME_DB_NON_POSTGRESQL = "RUNTIME_DB_NON_POSTGRESQL";
  public static final String RUNTIME_DB_ROLE_SUPERUSER = "RUNTIME_DB_ROLE_SUPERUSER";
  public static final String RUNTIME_DB_ROLE_CREATEROLE = "RUNTIME_DB_ROLE_CREATEROLE";
  public static final String RUNTIME_DB_ROLE_CREATEDB = "RUNTIME_DB_ROLE_CREATEDB";
  public static final String RUNTIME_DB_ROLE_BYPASSRLS = "RUNTIME_DB_ROLE_BYPASSRLS";
  public static final String RUNTIME_DB_OWNS_DATABASE = "RUNTIME_DB_OWNS_DATABASE";
  public static final String RUNTIME_DB_OWNS_SCHEMA = "RUNTIME_DB_OWNS_SCHEMA";
  public static final String PROTECTED_TABLE_MISSING = "PROTECTED_TABLE_MISSING";
  public static final String PROTECTED_TABLE_OWNED_BY_RUNTIME = "PROTECTED_TABLE_OWNED_BY_RUNTIME";
  public static final String FLYWAY_HISTORY_INSERT_GRANTED_TO_RUNTIME =
      "FLYWAY_HISTORY_INSERT_GRANTED_TO_RUNTIME";
  public static final String FLYWAY_HISTORY_UPDATE_GRANTED_TO_RUNTIME =
      "FLYWAY_HISTORY_UPDATE_GRANTED_TO_RUNTIME";
  public static final String FLYWAY_HISTORY_DELETE_GRANTED_TO_RUNTIME =
      "FLYWAY_HISTORY_DELETE_GRANTED_TO_RUNTIME";
  public static final String FLYWAY_HISTORY_TRUNCATE_GRANTED_TO_RUNTIME =
      "FLYWAY_HISTORY_TRUNCATE_GRANTED_TO_RUNTIME";
  public static final String AUDIT_UPDATE_GRANTED_TO_RUNTIME = "AUDIT_UPDATE_GRANTED_TO_RUNTIME";
  public static final String AUDIT_DELETE_GRANTED_TO_RUNTIME = "AUDIT_DELETE_GRANTED_TO_RUNTIME";
  public static final String AUDIT_TRUNCATE_GRANTED_TO_RUNTIME = "AUDIT_TRUNCATE_GRANTED_TO_RUNTIME";
  public static final String BACKUP_ARTIFACT_DELETE_GRANTED_TO_RUNTIME =
      "BACKUP_ARTIFACT_DELETE_GRANTED_TO_RUNTIME";
  public static final String BACKUP_ARTIFACT_TRUNCATE_GRANTED_TO_RUNTIME =
      "BACKUP_ARTIFACT_TRUNCATE_GRANTED_TO_RUNTIME";
  public static final String LIFECYCLE_OPERATION_DELETE_GRANTED_TO_RUNTIME =
      "LIFECYCLE_OPERATION_DELETE_GRANTED_TO_RUNTIME";
  public static final String LIFECYCLE_OPERATION_TRUNCATE_GRANTED_TO_RUNTIME =
      "LIFECYCLE_OPERATION_TRUNCATE_GRANTED_TO_RUNTIME";

  private static final List<String> PROTECTED_TABLES = List.of(
      "flyway_schema_history",
      "lifecycle_operation_audit",
      "backup_artifact",
      "lifecycle_operation");

  private DatabaseLeastPrivilegeValidator() {}

  public static RoleBoundarySnapshot inspect(JdbcTemplate jdbcTemplate) {
    String schema = jdbcTemplate.queryForObject("select current_schema()", String.class);
    RoleFlags flags = jdbcTemplate.queryForObject("""
        select rolsuper, rolcreatedb, rolcreaterole, rolbypassrls
        from pg_roles
        where rolname = current_user
        """, (rs, rowNum) -> new RoleFlags(
            rs.getBoolean("rolsuper"),
            rs.getBoolean("rolcreatedb"),
            rs.getBoolean("rolcreaterole"),
            rs.getBoolean("rolbypassrls")));
    if (flags == null) {
      throw new IllegalStateException("RUNTIME_DB_ROLE_NOT_FOUND");
    }
    boolean ownsDatabase = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
        select pg_get_userbyid(datdba) = current_user
        from pg_database
        where datname = current_database()
        """, Boolean.class));
    boolean ownsSchema = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
        select pg_get_userbyid(nspowner) = current_user
        from pg_namespace
        where nspname = current_schema()
        """, Boolean.class));

    List<TableBoundary> tables = new ArrayList<>();
    for (String table : PROTECTED_TABLES) {
      tables.add(inspectTable(jdbcTemplate, schema, table));
    }
    return new RoleBoundarySnapshot(flags, ownsDatabase, ownsSchema, tables);
  }

  public static boolean isPostgreSql(JdbcTemplate jdbcTemplate) {
    try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
      DatabaseMetaData metadata = connection.getMetaData();
      return metadata.getDatabaseProductName() != null
          && metadata.getDatabaseProductName().toLowerCase().contains("postgresql");
    } catch (SQLException ex) {
      throw new IllegalStateException("RUNTIME_DB_METADATA_UNAVAILABLE", ex);
    }
  }

  public static List<String> classify(RoleBoundarySnapshot snapshot, boolean flywayEnabled) {
    List<String> reasons = new ArrayList<>();
    if (flywayEnabled) {
      reasons.add(RUNTIME_FLYWAY_ENABLED);
    }
    RoleFlags flags = snapshot.roleFlags();
    if (flags.superuser()) {
      reasons.add(RUNTIME_DB_ROLE_SUPERUSER);
    }
    if (flags.createRole()) {
      reasons.add(RUNTIME_DB_ROLE_CREATEROLE);
    }
    if (flags.createDb()) {
      reasons.add(RUNTIME_DB_ROLE_CREATEDB);
    }
    if (flags.bypassRls()) {
      reasons.add(RUNTIME_DB_ROLE_BYPASSRLS);
    }
    if (snapshot.ownsDatabase()) {
      reasons.add(RUNTIME_DB_OWNS_DATABASE);
    }
    if (snapshot.ownsSchema()) {
      reasons.add(RUNTIME_DB_OWNS_SCHEMA);
    }
    for (TableBoundary table : snapshot.tables()) {
      classifyTable(table, reasons);
    }
    return reasons;
  }

  private static void classifyTable(TableBoundary table, List<String> reasons) {
    if (!table.exists()) {
      reasons.add(PROTECTED_TABLE_MISSING + ":" + table.name());
      return;
    }
    if (table.ownedByRuntime()) {
      reasons.add(PROTECTED_TABLE_OWNED_BY_RUNTIME + ":" + table.name());
    }
    if ("flyway_schema_history".equals(table.name())) {
      if (table.canInsert()) {
        reasons.add(FLYWAY_HISTORY_INSERT_GRANTED_TO_RUNTIME);
      }
      if (table.canUpdate()) {
        reasons.add(FLYWAY_HISTORY_UPDATE_GRANTED_TO_RUNTIME);
      }
      if (table.canDelete()) {
        reasons.add(FLYWAY_HISTORY_DELETE_GRANTED_TO_RUNTIME);
      }
      if (table.canTruncate()) {
        reasons.add(FLYWAY_HISTORY_TRUNCATE_GRANTED_TO_RUNTIME);
      }
    } else if ("lifecycle_operation_audit".equals(table.name())) {
      if (table.canUpdate()) {
        reasons.add(AUDIT_UPDATE_GRANTED_TO_RUNTIME);
      }
      if (table.canDelete()) {
        reasons.add(AUDIT_DELETE_GRANTED_TO_RUNTIME);
      }
      if (table.canTruncate()) {
        reasons.add(AUDIT_TRUNCATE_GRANTED_TO_RUNTIME);
      }
    } else if ("backup_artifact".equals(table.name())) {
      if (table.canDelete()) {
        reasons.add(BACKUP_ARTIFACT_DELETE_GRANTED_TO_RUNTIME);
      }
      if (table.canTruncate()) {
        reasons.add(BACKUP_ARTIFACT_TRUNCATE_GRANTED_TO_RUNTIME);
      }
    } else if ("lifecycle_operation".equals(table.name())) {
      if (table.canDelete()) {
        reasons.add(LIFECYCLE_OPERATION_DELETE_GRANTED_TO_RUNTIME);
      }
      if (table.canTruncate()) {
        reasons.add(LIFECYCLE_OPERATION_TRUNCATE_GRANTED_TO_RUNTIME);
      }
    }
  }

  private static TableBoundary inspectTable(JdbcTemplate jdbcTemplate, String schema, String table) {
    boolean exists = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
        select exists (
          select 1
          from pg_class c
          join pg_namespace n on n.oid = c.relnamespace
          where n.nspname = ?
            and c.relname = ?
            and c.relkind in ('r', 'p')
        )
        """, Boolean.class, schema, table));
    if (!exists) {
      return new TableBoundary(table, false, false, false, false, false, false);
    }
    String qualified = schema + "." + table;
    boolean owned = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
        select pg_get_userbyid(c.relowner) = current_user
        from pg_class c
        join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = ?
          and c.relname = ?
        """, Boolean.class, schema, table));
    boolean insert = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
        "select has_table_privilege(current_user, ?, 'INSERT')", Boolean.class, qualified));
    boolean update = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
        "select has_table_privilege(current_user, ?, 'UPDATE')", Boolean.class, qualified));
    boolean delete = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
        "select has_table_privilege(current_user, ?, 'DELETE')", Boolean.class, qualified));
    boolean truncate = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
        "select has_table_privilege(current_user, ?, 'TRUNCATE')", Boolean.class, qualified));
    return new TableBoundary(table, true, owned, insert, update, delete, truncate);
  }

  public record RoleFlags(boolean superuser, boolean createDb, boolean createRole, boolean bypassRls) {}

  public record TableBoundary(
      String name,
      boolean exists,
      boolean ownedByRuntime,
      boolean canInsert,
      boolean canUpdate,
      boolean canDelete,
      boolean canTruncate) {}

  public record RoleBoundarySnapshot(
      RoleFlags roleFlags,
      boolean ownsDatabase,
      boolean ownsSchema,
      List<TableBoundary> tables) {}
}
