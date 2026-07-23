package com.orderpilot.integration.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.orderpilot.application.services.control.lifecycle.BackupArtifactPersistenceService;
import com.orderpilot.application.services.control.lifecycle.BackupArtifactPersistenceService.FinalizeAvailableCommand;
import com.orderpilot.application.services.control.lifecycle.BackupArtifactPersistenceService.FinalizeFailureCommand;
import com.orderpilot.application.services.control.lifecycle.BackupArtifactPersistenceService.StageArtifactCommand;
import com.orderpilot.application.services.control.lifecycle.LifecycleBackupOperationService;
import com.orderpilot.application.services.control.lifecycle.LifecycleControlException;
import com.orderpilot.application.services.control.lifecycle.LifecycleOperationAuditor;
import com.orderpilot.domain.control.BackupArtifact;
import com.orderpilot.domain.control.BackupArtifact.AvailableMetadata;
import com.orderpilot.domain.control.BackupArtifactRepository;
import com.orderpilot.domain.control.BackupArtifactState;
import com.orderpilot.domain.control.LifecycleOperation;
import com.orderpilot.domain.control.LifecycleOperationAudit;
import com.orderpilot.domain.control.LifecycleOperationAuditEventType;
import com.orderpilot.domain.control.LifecycleOperationAuditPrincipalType;
import com.orderpilot.domain.control.LifecycleOperationAuditRepository;
import com.orderpilot.domain.control.LifecycleOperationRepository;
import com.orderpilot.domain.control.LifecycleOperationResultCode;
import com.orderpilot.domain.control.LifecycleOperationState;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof for P1-E2B-02 backup artifact authority and durable lifecycle audit. */
@Testcontainers
@RequiresPostgresIntegration
@EnabledIf("dockerAvailable")
class BackupArtifactAuthorityPostgresIntegrationTest extends DatabaseIntegrationTestBase {
  private static final String STAFF_FP = "staff-fingerprint-1";
  private static final String EXEC_FP = "executor-fingerprint-1";
  private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  static boolean dockerAvailable() {
    return DockerClientFactory.instance().isDockerAvailable();
  }

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 12);
    registry.add("orderpilot.control.lifecycle.executor.enabled", () -> true);
  }

  @Autowired private BackupArtifactPersistenceService artifactService;
  @Autowired private BackupArtifactRepository artifactRepository;
  @Autowired private LifecycleBackupOperationService lifecycleService;
  @Autowired private LifecycleOperationRepository operationRepository;
  @Autowired private LifecycleOperationAuditRepository auditRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private Flyway flyway;
  @SpyBean private LifecycleOperationAuditor auditor;

  private final AtomicInteger sequence = new AtomicInteger();

  @BeforeEach
  void clean() {
    reset(auditor);
    jdbcTemplate.update("delete from lifecycle_operation_audit");
    jdbcTemplate.update("delete from backup_artifact");
    jdbcTemplate.update("delete from lifecycle_operation");
  }

  @Test
  void blankPostgreSqlMigratesThroughV68AndV67UpgradesToV68WithValidChecksums() {
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from flyway_schema_history where version = '68' and success = true",
        Integer.class)).isEqualTo(1);

    migrateIsolatedSchema("p1e2b_blank_" + sequence.incrementAndGet(), null);
    migrateIsolatedSchema("p1e2b_prior_" + sequence.incrementAndGet(), "67");
  }

  @Test
  void v68CreatesRequiredTablesConstraintsAndIndexes() {
    assertThat(tableNames("backup_artifact", "lifecycle_operation_audit"))
        .containsExactly("backup_artifact", "lifecycle_operation_audit");
    assertThat(constraintNames()).contains(
        "ck_backup_artifact_state",
        "ck_backup_artifact_public_handle",
        "ck_backup_artifact_format",
        "ck_backup_artifact_available_metadata",
        "ck_lifecycle_operation_audit_event_type",
        "ck_lifecycle_operation_audit_metadata_bound");
    assertThat(indexNames()).contains(
        "ux_backup_artifact_public_handle",
        "ux_backup_artifact_storage_key",
        "ux_backup_artifact_one_available_per_operation",
        "idx_backup_artifact_lifecycle_operation",
        "idx_backup_artifact_state_created",
        "idx_lifecycle_operation_audit_operation_order",
        "idx_lifecycle_operation_audit_artifact_order");
  }

  @Test
  void stagedArtifactPersistsAsNonAuthoritativeAndAuditsWithBoundedRepositoryApi() {
    LifecycleOperation leased = leasedOperation("idem-stage");

    BackupArtifact artifact = artifactService.stageArtifact(stageCommand(leased, handle(1), "staged/one.dump.enc"));

    assertThat(artifact.getId()).isNotNull();
    assertThat(artifact.getState()).isEqualTo(BackupArtifactState.STAGED);
    assertThat(artifact.isAuthoritative()).isFalse();
    assertThat(artifact.getBackupFormat()).isEqualTo(BackupArtifact.POSTGRES_CUSTOM_FORMAT);
    assertThat(artifact.getStorageKey()).isEqualTo("staged/one.dump.enc");
    assertThat(artifact.getExecutionAttempt()).isEqualTo(leased.getAttempt());
    assertThat(artifact.getFencingToken()).isEqualTo(leased.getFencingToken());
    assertThat(auditEvents(leased)).contains(LifecycleOperationAuditEventType.BACKUP_ARTIFACT_STAGED);
    assertRepositoryHasNoUpdateOrDeleteApi(LifecycleOperationAuditRepository.class);
    assertRepositoryHasNoUpdateOrDeleteApi(BackupArtifactRepository.class);
  }

  @Test
  void availableMetadataAndUniquenessAreDatabaseEnforced() {
    LifecycleOperation leased = leasedOperation("idem-constraints");
    UUID opId = leased.getId();

    assertThatThrownBy(() -> insertAvailable(handle(2), opId, "available/no-digest.dump.enc", null, 100L, true,
        "AES-256-GCM", "v1", "key-1", Instant.now()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertAvailable(handle(3), opId, "available/bad-digest.dump.enc", "abc", 100L, true,
        "AES-256-GCM", "v1", "key-1", Instant.now()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertAvailable(handle(4), opId, "available/zero.dump.enc", SHA, 0L, true,
        "AES-256-GCM", "v1", "key-1", Instant.now()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertAvailable(handle(5), opId, "available/not-validated.dump.enc", SHA, 100L, false,
        "AES-256-GCM", "v1", "key-1", Instant.now()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertAvailable(handle(6), opId, "available/no-encryption.dump.enc", SHA, 100L, true,
        null, "v1", "key-1", Instant.now()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertAvailable(handle(7), opId, "available/no-time.dump.enc", SHA, 100L, true,
        "AES-256-GCM", "v1", "key-1", null))
        .isInstanceOf(DataIntegrityViolationException.class);

    insertStaged(handle(8), opId, "staged/dup-handle-a.dump.enc");
    assertThatThrownBy(() -> insertStaged(handle(8), opId, "staged/dup-handle-b.dump.enc"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertStaged(handle(9), opId, "staged/dup-handle-a.dump.enc"))
        .isInstanceOf(DataIntegrityViolationException.class);

    insertAvailable(handle(10), opId, "available/first.dump.enc", SHA, 100L, true,
        "AES-256-GCM", "v1", "key-1", Instant.now());
    assertThatThrownBy(() -> insertAvailable(handle(11), opId, "available/second.dump.enc", SHA, 100L, true,
        "AES-256-GCM", "v1", "key-1", Instant.now()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertStaged(handle(12), UUID.randomUUID(), "staged/missing-fk.dump.enc"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void nonBackupLifecycleAssociationIsDeniedBySchemaAndServiceOnlyUsesBackupOperations() {
    assertThatThrownBy(() -> jdbcTemplate.update("""
        insert into lifecycle_operation (
          public_id, operation_type, state, idempotency_key_hash, requested_by_fingerprint,
          attempt, created_at, updated_at
        ) values ('op_restore_forbidden', 'RESTORE', repeat('a', 64), repeat('b', 64),
          repeat('c', 64), 0, now(), now())
        """))
        .isInstanceOf(DataIntegrityViolationException.class);

    LifecycleOperation leased = leasedOperation("idem-backup-only");
    BackupArtifact artifact = artifactService.stageArtifact(stageCommand(leased, handle(13), "staged/backup-only.dump.enc"));
    assertThat(artifact.getLifecycleOperation().getOperationType().name()).isEqualTo("BACKUP");
  }

  @Test
  void lifecycleRequestAndLeaseAuditCommitInSameTransactions() {
    LifecycleOperation requested = lifecycleService.requestBackup(STAFF_FP, "idem-audit-request");
    LifecycleOperation leased = lifecycleService.leaseNext(EXEC_FP).orElseThrow();

    assertThat(leased.getPublicId()).isEqualTo(requested.getPublicId());
    assertThat(auditEvents(requested)).containsExactly(
        LifecycleOperationAuditEventType.BACKUP_REQUESTED,
        LifecycleOperationAuditEventType.BACKUP_LEASE_ACQUIRED);
  }

  @Test
  void requestAuditFailureRollsBackOperationCreation() {
    doThrow(new RuntimeException("AUDIT_FAIL")).when(auditor).backupRequested(any(), eq(STAFF_FP));

    assertThatThrownBy(() -> lifecycleService.requestBackup(STAFF_FP, "idem-request-rollback"))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("AUDIT_FAIL");

    assertThat(operationRepository.count()).isZero();
    assertThat(auditRepository.count()).isZero();
  }

  @Test
  void stagedAuditFailureRollsBackArtifactCreation() {
    LifecycleOperation leased = leasedOperation("idem-stage-rollback");
    doThrow(new RuntimeException("AUDIT_FAIL")).when(auditor).artifactStaged(any(), any(), eq(EXEC_FP));

    assertThatThrownBy(() -> artifactService.stageArtifact(stageCommand(leased, handle(14), "staged/rollback.dump.enc")))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("AUDIT_FAIL");

    assertThat(artifactRepository.count()).isZero();
    assertThat(auditEvents(leased)).doesNotContain(LifecycleOperationAuditEventType.BACKUP_ARTIFACT_STAGED);
  }

  @Test
  void availableSucceededAndSuccessAuditsCommitTogether() {
    LifecycleOperation leased = leasedOperation("idem-available");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased, handle(15), "staged/available.dump.enc"));

    BackupArtifact available = artifactService.makeArtifactAvailableAndComplete(
        availableCommand(leased, staged.getPublicHandle()));

    LifecycleOperation done = operationRepository.findByPublicId(leased.getPublicId()).orElseThrow();
    assertThat(available.getState()).isEqualTo(BackupArtifactState.AVAILABLE);
    assertThat(available.isAuthoritative()).isTrue();
    assertThat(done.getState()).isEqualTo(LifecycleOperationState.SUCCEEDED);
    assertThat(done.getResultCode()).isEqualTo(LifecycleOperationResultCode.BACKUP_COMPLETED);
    assertThat(auditEvents(leased)).contains(
        LifecycleOperationAuditEventType.BACKUP_ARTIFACT_AVAILABLE,
        LifecycleOperationAuditEventType.BACKUP_SUCCEEDED);
  }

  @Test
  void artifactAvailableAuditFailureRollsBackAvailableAndSucceeded() {
    LifecycleOperation leased = leasedOperation("idem-available-rollback");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased, handle(16), "staged/available-rollback.dump.enc"));
    doThrow(new RuntimeException("AUDIT_FAIL")).when(auditor).artifactAvailable(any(), any(), eq(EXEC_FP));

    assertThatThrownBy(() -> artifactService.makeArtifactAvailableAndComplete(
        availableCommand(leased, staged.getPublicHandle())))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("AUDIT_FAIL");

    BackupArtifact reloaded = artifactRepository.findByPublicHandle(staged.getPublicHandle()).orElseThrow();
    LifecycleOperation operation = operationRepository.findByPublicId(leased.getPublicId()).orElseThrow();
    assertThat(reloaded.getState()).isEqualTo(BackupArtifactState.STAGED);
    assertThat(operation.getState()).isEqualTo(LifecycleOperationState.LEASED);
    assertThat(auditEvents(leased)).doesNotContain(
        LifecycleOperationAuditEventType.BACKUP_ARTIFACT_AVAILABLE,
        LifecycleOperationAuditEventType.BACKUP_SUCCEEDED);
  }

  @Test
  void operationSuccessAuditFailureRollsBackAvailableTransition() {
    LifecycleOperation leased = leasedOperation("idem-success-rollback");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased, handle(17), "staged/success-rollback.dump.enc"));
    doThrow(new RuntimeException("AUDIT_FAIL")).when(auditor).operationSucceeded(any(), eq(EXEC_FP));

    assertThatThrownBy(() -> artifactService.makeArtifactAvailableAndComplete(
        availableCommand(leased, staged.getPublicHandle())))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("AUDIT_FAIL");

    BackupArtifact reloaded = artifactRepository.findByPublicHandle(staged.getPublicHandle()).orElseThrow();
    LifecycleOperation operation = operationRepository.findByPublicId(leased.getPublicId()).orElseThrow();
    assertThat(reloaded.getState()).isEqualTo(BackupArtifactState.STAGED);
    assertThat(operation.getState()).isEqualTo(LifecycleOperationState.LEASED);
    assertThat(auditEvents(leased)).doesNotContain(LifecycleOperationAuditEventType.BACKUP_SUCCEEDED);
  }

  @Test
  void failedOperationAndFailureAuditCommitWithoutAuthoritativeArtifact() {
    LifecycleOperation leased = leasedOperation("idem-failed");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased, handle(18), "staged/failed.dump.enc"));

    LifecycleOperation failed = artifactService.failOperation(new FinalizeFailureCommand(
        leased.getPublicId(), EXEC_FP, leased.getFencingToken(), staged.getPublicHandle(),
        LifecycleOperationResultCode.BACKUP_FAILED_EXECUTION, "BACKUP_PROCESS_FAILED"));

    BackupArtifact rejected = artifactRepository.findByPublicHandle(staged.getPublicHandle()).orElseThrow();
    assertThat(failed.getState()).isEqualTo(LifecycleOperationState.FAILED);
    assertThat(rejected.getState()).isEqualTo(BackupArtifactState.REJECTED);
    assertThat(rejected.isAuthoritative()).isFalse();
    assertThat(auditEvents(leased)).contains(
        LifecycleOperationAuditEventType.BACKUP_ARTIFACT_REJECTED,
        LifecycleOperationAuditEventType.BACKUP_FAILED);
    assertThat(auditEvents(leased)).doesNotContain(LifecycleOperationAuditEventType.BACKUP_SUCCEEDED);
    assertThat(artifactRepository.findByLifecycleOperationIdAndState(leased.getId(), BackupArtifactState.AVAILABLE))
        .isEmpty();
  }

  @Test
  void auditMetadataBoundsAndOrderingAreEnforced() {
    LifecycleOperation leased = leasedOperation("idem-audit-bounds");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased, handle(19), "staged/audit-bounds.dump.enc"));

    assertThat(auditRepository.findTop100ByLifecycleOperationIdOrderByCreatedAtAscIdAsc(leased.getId()))
        .extracting(LifecycleOperationAudit::getEventType)
        .containsExactly(
            LifecycleOperationAuditEventType.BACKUP_REQUESTED,
            LifecycleOperationAuditEventType.BACKUP_LEASE_ACQUIRED,
            LifecycleOperationAuditEventType.BACKUP_ARTIFACT_STAGED);
    assertThat(auditRepository.findTop100ByBackupArtifactIdOrderByCreatedAtAscIdAsc(staged.getId()))
        .extracting(LifecycleOperationAudit::getEventType)
        .containsExactly(LifecycleOperationAuditEventType.BACKUP_ARTIFACT_STAGED);
    assertThatThrownBy(() -> new LifecycleOperationAudit(
        leased,
        staged,
        LifecycleOperationAuditEventType.BACKUP_ARTIFACT_STAGED,
        LifecycleOperationAuditPrincipalType.EXECUTOR,
        EXEC_FP,
        null,
        "{\"x\":\"" + "a".repeat(2050) + "\"}",
        Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void migrateIsolatedSchema(String schema, String firstTarget) {
    Flyway first = flywayForSchema(schema, firstTarget);
    first.migrate();
    assertThat(first.validateWithResult().validationSuccessful).isTrue();
    if (firstTarget != null) {
      Flyway latest = flywayForSchema(schema, null);
      latest.migrate();
      assertThat(latest.validateWithResult().validationSuccessful).isTrue();
    }
    Integer reached = jdbcTemplate.queryForObject(
        "select count(*) from " + schema + ".flyway_schema_history where version = '68' and success = true",
        Integer.class);
    assertThat(reached).isEqualTo(1);
  }

  private Flyway flywayForSchema(String schema, String target) {
    var configuration = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(schema)
        .createSchemas(true)
        .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    return configuration.load();
  }

  private LifecycleOperation leasedOperation(String idempotencyKey) {
    lifecycleService.requestBackup(STAFF_FP, idempotencyKey);
    return lifecycleService.leaseNext(EXEC_FP).orElseThrow();
  }

  private StageArtifactCommand stageCommand(LifecycleOperation operation, String handle, String storageKey) {
    return new StageArtifactCommand(
        operation.getPublicId(), EXEC_FP, operation.getFencingToken(), handle, storageKey);
  }

  private FinalizeAvailableCommand availableCommand(LifecycleOperation operation, String artifactHandle) {
    return new FinalizeAvailableCommand(
        operation.getPublicId(),
        EXEC_FP,
        operation.getFencingToken(),
        artifactHandle,
        new AvailableMetadata(
            "AES-256-GCM",
            "v1",
            "backup-key-2026-07",
            "PostgreSQL 16",
            "pg_dump 16",
            "pg_restore 16",
            "V68",
            128L,
            SHA,
            true,
            12));
  }

  private String handle(int value) {
    return "ba_" + String.format("%024x", value);
  }

  private List<String> tableNames(String... names) {
    return Arrays.stream(names)
        .map(name -> jdbcTemplate.queryForObject("select to_regclass(?)::text", String.class, name))
        .toList();
  }

  private List<String> constraintNames() {
    return jdbcTemplate.queryForList("""
        select constraint_name
        from information_schema.table_constraints
        where table_name in ('backup_artifact', 'lifecycle_operation_audit')
        """, String.class);
  }

  private List<String> indexNames() {
    return jdbcTemplate.queryForList("""
        select indexname
        from pg_indexes
        where tablename in ('backup_artifact', 'lifecycle_operation_audit')
        """, String.class);
  }

  private List<LifecycleOperationAuditEventType> auditEvents(LifecycleOperation operation) {
    return auditRepository.findTop100ByLifecycleOperationIdOrderByCreatedAtAscIdAsc(operation.getId())
        .stream()
        .map(LifecycleOperationAudit::getEventType)
        .toList();
  }

  private void assertRepositoryHasNoUpdateOrDeleteApi(Class<?> repositoryType) {
    assertThat(Arrays.stream(repositoryType.getMethods()).map(Method::getName))
        .noneMatch(name -> name.startsWith("delete") || name.startsWith("update"));
  }

  private void insertStaged(String publicHandle, UUID operationId, String storageKey) {
    jdbcTemplate.update("""
        insert into backup_artifact (
          public_handle, lifecycle_operation_id, state, backup_format, created_at, updated_at,
          storage_key, execution_attempt, fencing_token
        ) values (?, ?, 'STAGED', 'POSTGRES_CUSTOM', now(), now(), ?, 1, 1)
        """, publicHandle, operationId, storageKey);
  }

  private void insertAvailable(
      String publicHandle,
      UUID operationId,
      String storageKey,
      String digest,
      Long encryptedByteSize,
      Boolean archiveValidated,
      String encryptionAlgorithm,
      String envelopeVersion,
      String keyIdentifier,
      Instant availableAt) {
    Timestamp timestamp = availableAt == null ? null : Timestamp.from(availableAt);
    jdbcTemplate.update("""
        insert into backup_artifact (
          public_handle, lifecycle_operation_id, state, backup_format, encryption_algorithm,
          encryption_envelope_version, encryption_key_identifier, created_at, updated_at, available_at,
          encrypted_byte_size, ciphertext_sha256, archive_validated, archive_entry_count, storage_key,
          execution_attempt, fencing_token
        ) values (?, ?, 'AVAILABLE', 'POSTGRES_CUSTOM', ?, ?, ?, coalesce(?, now()), coalesce(?, now()), ?, ?, ?, ?, 1, ?, 1, 1)
        """,
        publicHandle,
        operationId,
        encryptionAlgorithm,
        envelopeVersion,
        keyIdentifier,
        timestamp,
        timestamp,
        timestamp,
        encryptedByteSize,
        digest,
        archiveValidated,
        storageKey);
  }
}
