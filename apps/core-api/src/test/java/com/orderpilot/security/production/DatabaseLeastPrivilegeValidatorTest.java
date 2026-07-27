package com.orderpilot.security.production;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.security.production.DatabaseLeastPrivilegeValidator.RoleBoundarySnapshot;
import com.orderpilot.security.production.DatabaseLeastPrivilegeValidator.RoleFlags;
import com.orderpilot.security.production.DatabaseLeastPrivilegeValidator.TableBoundary;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseLeastPrivilegeValidatorTest {

  @Test
  void acceptsRestrictedRuntimeRoleClassification() {
    RoleBoundarySnapshot snapshot = snapshot(
        new RoleFlags(false, false, false, false),
        false,
        false,
        new TableBoundary("lifecycle_operation_audit", true, false, false, false, false),
        new TableBoundary("backup_artifact", true, false, true, false, false),
        new TableBoundary("lifecycle_operation", true, false, true, false, false));

    assertThat(DatabaseLeastPrivilegeValidator.classify(snapshot, false)).isEmpty();
  }

  @Test
  void rejectsFlywayEnabledAndRoleAttributes() {
    RoleBoundarySnapshot snapshot = snapshot(
        new RoleFlags(true, true, true, true),
        true,
        true,
        new TableBoundary("lifecycle_operation_audit", true, false, false, false, false),
        new TableBoundary("backup_artifact", true, false, true, false, false),
        new TableBoundary("lifecycle_operation", true, false, true, false, false));

    assertThat(DatabaseLeastPrivilegeValidator.classify(snapshot, true)).contains(
        DatabaseLeastPrivilegeValidator.RUNTIME_FLYWAY_ENABLED,
        DatabaseLeastPrivilegeValidator.RUNTIME_DB_ROLE_SUPERUSER,
        DatabaseLeastPrivilegeValidator.RUNTIME_DB_ROLE_CREATEDB,
        DatabaseLeastPrivilegeValidator.RUNTIME_DB_ROLE_CREATEROLE,
        DatabaseLeastPrivilegeValidator.RUNTIME_DB_ROLE_BYPASSRLS,
        DatabaseLeastPrivilegeValidator.RUNTIME_DB_OWNS_DATABASE,
        DatabaseLeastPrivilegeValidator.RUNTIME_DB_OWNS_SCHEMA);
  }

  @Test
  void rejectsProtectedTableOwnershipAndForbiddenPrivileges() {
    RoleBoundarySnapshot snapshot = snapshot(
        new RoleFlags(false, false, false, false),
        false,
        false,
        new TableBoundary("lifecycle_operation_audit", true, true, true, true, true),
        new TableBoundary("backup_artifact", true, true, true, true, true),
        new TableBoundary("lifecycle_operation", true, true, true, true, true));

    assertThat(DatabaseLeastPrivilegeValidator.classify(snapshot, false)).contains(
        DatabaseLeastPrivilegeValidator.PROTECTED_TABLE_OWNED_BY_RUNTIME + ":lifecycle_operation_audit",
        DatabaseLeastPrivilegeValidator.PROTECTED_TABLE_OWNED_BY_RUNTIME + ":backup_artifact",
        DatabaseLeastPrivilegeValidator.PROTECTED_TABLE_OWNED_BY_RUNTIME + ":lifecycle_operation",
        DatabaseLeastPrivilegeValidator.AUDIT_UPDATE_GRANTED_TO_RUNTIME,
        DatabaseLeastPrivilegeValidator.AUDIT_DELETE_GRANTED_TO_RUNTIME,
        DatabaseLeastPrivilegeValidator.AUDIT_TRUNCATE_GRANTED_TO_RUNTIME,
        DatabaseLeastPrivilegeValidator.BACKUP_ARTIFACT_DELETE_GRANTED_TO_RUNTIME,
        DatabaseLeastPrivilegeValidator.BACKUP_ARTIFACT_TRUNCATE_GRANTED_TO_RUNTIME,
        DatabaseLeastPrivilegeValidator.LIFECYCLE_OPERATION_DELETE_GRANTED_TO_RUNTIME,
        DatabaseLeastPrivilegeValidator.LIFECYCLE_OPERATION_TRUNCATE_GRANTED_TO_RUNTIME);
  }

  @Test
  void rejectsMissingProtectedTable() {
    RoleBoundarySnapshot snapshot = snapshot(
        new RoleFlags(false, false, false, false),
        false,
        false,
        new TableBoundary("lifecycle_operation_audit", false, false, false, false, false),
        new TableBoundary("backup_artifact", true, false, true, false, false),
        new TableBoundary("lifecycle_operation", true, false, true, false, false));

    assertThat(DatabaseLeastPrivilegeValidator.classify(snapshot, false))
        .containsExactly(DatabaseLeastPrivilegeValidator.PROTECTED_TABLE_MISSING + ":lifecycle_operation_audit");
  }

  private static RoleBoundarySnapshot snapshot(
      RoleFlags flags,
      boolean ownsDatabase,
      boolean ownsSchema,
      TableBoundary... tables) {
    return new RoleBoundarySnapshot(flags, ownsDatabase, ownsSchema, List.of(tables));
  }
}
