package com.orderpilot.integration.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
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
import com.orderpilot.support.LifecyclePostgresTestSupport;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof for P1-E2B-02 backup artifact authority and durable lifecycle audit. */
@Testcontainers
@RequiresPostgresIntegration
class BackupArtifactAuthorityPostgresIntegrationTest extends DatabaseIntegrationTestBase {
  private static final String STAFF_FP = "staff-fingerprint-1";
  private static final String EXEC_FP = "executor-fingerprint-1";
  private static final String SECOND_EXEC_FP = "executor-fingerprint-2";
  private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) {
    LifecyclePostgresTestSupport.register(registry);
  }

  @Autowired private BackupArtifactPersistenceService artifactService;
  @Autowired private BackupArtifactRepository artifactRepository;
  @Autowired private LifecycleBackupOperationService lifecycleService;
  @Autowired private LifecycleOperationRepository operationRepository;
  @Autowired private LifecycleOperationAuditRepository auditRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private Flyway flyway;
  @Autowired private PlatformTransactionManager transactionManager;
  @SpyBean private LifecycleOperationAuditor auditor;

  private final AtomicInteger sequence = new AtomicInteger();

  @BeforeEach
  void clean() {
    reset(auditor);
    // lifecycle_operation_audit is append-only: the V68 BEFORE UPDATE OR DELETE trigger
    // (trg_lifecycle_operation_audit_append_only) rejects any row DELETE. Test isolation therefore uses
    // TRUNCATE, which does not fire row-level triggers, instead of a forbidden production-style delete.
    jdbcTemplate.update("truncate table lifecycle_operation_audit");
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
  void v68RejectsLegacySuccessfulBackupWithoutArtifactEvidence() {
    String schema = "p1e2b_legacy_success_" + sequence.incrementAndGet();
    try {
      flywayForSchema(schema, "67").migrate();
      jdbcTemplate.update("""
          insert into %s.lifecycle_operation (
            public_id, operation_type, state, idempotency_key_hash, requested_by_fingerprint,
            result_code, attempt, fencing_token, lease_expires_at, leased_by_fingerprint,
            created_at, updated_at
          ) values ('op_legacy_success', 'BACKUP', 'SUCCEEDED', repeat('a', 64), repeat('b', 64),
            'BACKUP_COMPLETED', 1, 1, now() + interval '5 minutes', repeat('c', 64), now(), now())
          """.formatted(schema));
      Map<String, Object> before = jdbcTemplate.queryForMap(
          "select public_id, operation_type, state, result_code, attempt, fencing_token "
              + "from " + schema + ".lifecycle_operation where public_id = 'op_legacy_success'");

      Throwable thrown = catchThrowable(() -> flywayForSchema(schema, null).migrate());

      assertThat(thrown)
          .hasMessageContaining("V68_LEGACY_BACKUP_SUCCESS_REQUIRES_RECONCILIATION");
      assertThat(jdbcTemplate.queryForObject("select to_regclass(?)::text", String.class, schema + ".backup_artifact"))
          .isNull();
      assertThat(jdbcTemplate.queryForObject(
          "select to_regclass(?)::text", String.class, schema + ".lifecycle_operation_audit"))
          .isNull();
      assertThat(jdbcTemplate.queryForObject(
          "select count(*) from " + schema + ".flyway_schema_history where version = '68' and success = true",
          Integer.class)).isZero();
      assertThat(jdbcTemplate.queryForMap(
          "select public_id, operation_type, state, result_code, attempt, fencing_token "
              + "from " + schema + ".lifecycle_operation where public_id = 'op_legacy_success'"))
          .isEqualTo(before);
    } finally {
      jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void v68AllowsNonSuccessfulLegacyRowsWithoutFabricatingAvailableArtifacts() {
    String schema = "p1e2b_legacy_non_success_" + sequence.incrementAndGet();
    try {
      flywayForSchema(schema, "67").migrate();
      jdbcTemplate.update("""
          insert into %s.lifecycle_operation (
            public_id, operation_type, state, idempotency_key_hash, requested_by_fingerprint,
            result_code, attempt, fencing_token, lease_expires_at, leased_by_fingerprint,
            created_at, updated_at
          ) values
            ('op_legacy_queued', 'BACKUP', 'QUEUED', repeat('1', 64), repeat('2', 64),
              null, 0, null, null, null, now(), now()),
            ('op_legacy_failed', 'BACKUP', 'FAILED', repeat('3', 64), repeat('4', 64),
              'BACKUP_FAILED_EXECUTION', 1, 1, now() + interval '5 minutes', repeat('5', 64), now(), now())
          """.formatted(schema));

      Flyway latest = flywayForSchema(schema, null);
      latest.migrate();

      assertThat(latest.validateWithResult().validationSuccessful).isTrue();
      assertThat(jdbcTemplate.queryForObject(
          "select count(*) from " + schema + ".flyway_schema_history where version = '68' and success = true",
          Integer.class)).isEqualTo(1);
      assertThat(jdbcTemplate.queryForObject(
          "select count(*) from " + schema + ".backup_artifact where state = 'AVAILABLE'",
          Integer.class)).isZero();
    } finally {
      jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }
  @Test
  void v68CreatesRequiredTablesConstraintsAndIndexes() {
    assertThat(tableNames("backup_artifact", "lifecycle_operation_audit"))
        .containsExactly("backup_artifact", "lifecycle_operation_audit");
    assertThat(constraintNames()).contains(
        "ck_backup_artifact_state",
        "ck_backup_artifact_public_handle",
        "ck_backup_artifact_format",
        "ck_backup_artifact_state_specific",
        "ck_backup_artifact_execution_identity",
        "ck_backup_artifact_closed_encryption",
        "ck_lifecycle_operation_audit_event_type",
        "ck_lifecycle_operation_audit_metadata_bound",
        "ck_lifecycle_operation_audit_artifact_contract",
        "ck_lifecycle_operation_audit_result_contract");
    assertThat(indexNames()).contains(
        "ux_backup_artifact_public_handle",
        "ux_backup_artifact_storage_key",
        "ux_backup_artifact_execution_identity",
        "ux_backup_artifact_identity_fk",
        "ux_backup_artifact_one_available_per_operation",
        "idx_backup_artifact_lifecycle_operation",
        "idx_backup_artifact_state_created",
        "idx_lifecycle_operation_audit_operation_order",
        "idx_lifecycle_operation_audit_artifact_order");
  }

  @Test
  void stagedArtifactPersistsAsNonAuthoritativeAndAuditsWithBoundedRepositoryApi() {
    LifecycleOperation leased = leasedOperation("idem-stage");

    BackupArtifact artifact = artifactService.stageArtifact(stageCommand(leased));

    assertThat(artifact.getId()).isNotNull();
    assertThat(artifact.getState()).isEqualTo(BackupArtifactState.STAGED);
    assertThat(artifact.isAuthoritative()).isFalse();
    assertThat(artifact.getBackupFormat()).isEqualTo(BackupArtifact.POSTGRES_CUSTOM_FORMAT);
    assertThat(artifact.getPublicHandle()).startsWith("ba_");
    assertThat(artifact.getStorageKey()).contains(leased.getPublicId());
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

    insertStaged(handle(8), opId, "staged/dup-handle-a.dump.enc", 201, 201L);
    assertThatThrownBy(() -> insertStaged(handle(8), opId, "staged/dup-handle-b.dump.enc", 202, 202L))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertStaged(handle(9), opId, "staged/dup-handle-a.dump.enc", 203, 203L))
        .isInstanceOf(DataIntegrityViolationException.class);

    insertAvailable(handle(10), opId, "available/first.dump.enc", SHA, 100L, true,
        "AES-256-GCM", "v1", "key-1", Instant.now(), 301, 301L);
    assertThatThrownBy(() -> insertAvailable(handle(11), opId, "available/second.dump.enc", SHA, 100L, true,
        "AES-256-GCM", "v1", "key-1", Instant.now(), 302, 302L))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertStaged(handle(12), UUID.randomUUID(), "staged/missing-fk.dump.enc", 401, 401L))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void nonBackupLifecycleAssociationIsDeniedBySchemaAndServiceOnlyUsesBackupOperations() {
    assertThatThrownBy(() -> jdbcTemplate.update("""
        insert into lifecycle_operation (
          public_id, operation_type, state, idempotency_key_hash, requested_by_fingerprint,
          attempt, created_at, updated_at
        ) values ('op_restore_forbidden', 'RESTORE', 'QUEUED', repeat('b', 64),
          repeat('c', 64), 0, now(), now())
        """))
        .isInstanceOf(DataIntegrityViolationException.class);

    LifecycleOperation leased = leasedOperation("idem-backup-only");
    BackupArtifact artifact = artifactService.stageArtifact(stageCommand(leased));
    assertThat(artifact.getLifecycleOperation().getOperationType().name()).isEqualTo("BACKUP");
  }

  @Test
  void lifecycleRequestAndLeaseAuditCommitInSameTransactions() {
    LifecycleOperation requested = lifecycleService.requestBackup(STAFF_FP, "idem-audit-request");
    LifecycleOperation leased = lifecycleService.leaseNext(EXEC_FP).orElseThrow();

    assertThat(leased.getPublicId()).isEqualTo(requested.getPublicId());
    assertThat(auditEvents(requested)).containsExactly(
        LifecycleOperationAuditEventType.BACKUP_LEASE_ACQUIRED,
        LifecycleOperationAuditEventType.BACKUP_REQUESTED);
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

    assertThatThrownBy(() -> artifactService.stageArtifact(stageCommand(leased)))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("AUDIT_FAIL");

    assertThat(artifactRepository.count()).isZero();
    assertThat(auditEvents(leased)).doesNotContain(LifecycleOperationAuditEventType.BACKUP_ARTIFACT_STAGED);
  }

  @Test
  void availableSucceededAndSuccessAuditsCommitTogether() {
    LifecycleOperation leased = leasedOperation("idem-available");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased));

    BackupArtifact available = artifactService.makeArtifactAvailableAndComplete(availableCommand(leased, staged));

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
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased));
    doThrow(new RuntimeException("AUDIT_FAIL")).when(auditor).artifactAvailable(any(), any(), eq(EXEC_FP));

    assertThatThrownBy(() -> artifactService.makeArtifactAvailableAndComplete(availableCommand(leased, staged)))
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
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased));
    doThrow(new RuntimeException("AUDIT_FAIL")).when(auditor).operationSucceeded(any(), eq(EXEC_FP));

    assertThatThrownBy(() -> artifactService.makeArtifactAvailableAndComplete(availableCommand(leased, staged)))
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
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased));

    LifecycleOperation failed = artifactService.failOperation(new FinalizeFailureCommand(
        leased.getPublicId(), EXEC_FP, leased.getFencingToken(), staged.getPublicHandle(),
        LifecycleOperationResultCode.BACKUP_FAILED_EXECUTION));

    BackupArtifact rejected = artifactRepository.findByPublicHandle(staged.getPublicHandle()).orElseThrow();
    assertThat(failed.getState()).isEqualTo(LifecycleOperationState.FAILED);
    assertThat(rejected.getState()).isEqualTo(BackupArtifactState.REJECTED);
    assertThat(rejected.getFailureCode()).isEqualTo("BACKUP_FAILED_EXECUTION");
    assertThat(rejected.isAuthoritative()).isFalse();
    assertThat(auditEvents(leased)).contains(
        LifecycleOperationAuditEventType.BACKUP_ARTIFACT_REJECTED,
        LifecycleOperationAuditEventType.BACKUP_FAILED);
    assertThat(auditEvents(leased)).doesNotContain(LifecycleOperationAuditEventType.BACKUP_SUCCEEDED);
    assertThat(artifactRepository.findByLifecycleOperationIdAndState(leased.getId(), BackupArtifactState.AVAILABLE))
        .isEmpty();
  }

  @Test
  void omittedArtifactHandleOnFailureRejectsCurrentStagedArtifact() {
    LifecycleOperation leased = leasedOperation("idem-omit-fail");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased));

    LifecycleOperation failed = artifactService.failOperation(new FinalizeFailureCommand(
        leased.getPublicId(), EXEC_FP, leased.getFencingToken(), null,
        LifecycleOperationResultCode.BACKUP_TIMED_OUT));

    BackupArtifact rejected = artifactRepository.findByPublicHandle(staged.getPublicHandle()).orElseThrow();
    assertThat(failed.getState()).isEqualTo(LifecycleOperationState.FAILED);
    assertThat(rejected.getState()).isEqualTo(BackupArtifactState.REJECTED);
    assertThat(rejected.getFailureCode()).isEqualTo("BACKUP_TIMED_OUT");
  }

  @Test
  void exactSuccessAndFailureReplayAreIdempotentAndChangedFingerprintConflicts() {
    LifecycleOperation leased = leasedOperation("idem-replay");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased));
    FinalizeAvailableCommand success = availableCommand(leased, staged);

    BackupArtifact first = artifactService.makeArtifactAvailableAndComplete(success);
    BackupArtifact replay = artifactService.makeArtifactAvailableAndComplete(success);
    assertThat(replay.getId()).isEqualTo(first.getId());
    assertThat(artifactRepository.count()).isEqualTo(1);

    AvailableMetadata changed = new AvailableMetadata(
        success.metadata().encryptionAlgorithm(),
        success.metadata().encryptionEnvelopeVersion(),
        success.metadata().encryptionKeyIdentifier(),
        success.metadata().postgresServerVersion(),
        success.metadata().pgDumpVersion(),
        success.metadata().pgRestoreVersion(),
        success.metadata().schemaVersion(),
        256L,
        SHA,
        true,
        12);
    assertThatThrownBy(() -> artifactService.makeArtifactAvailableAndComplete(new FinalizeAvailableCommand(
            leased.getPublicId(), EXEC_FP, leased.getFencingToken(),
            staged.getPublicHandle(), staged.getStorageKey(), changed)))
        .isInstanceOf(LifecycleControlException.class);

    LifecycleOperation failedLease = leasedOperation("idem-fail-replay");
    BackupArtifact failedStaged = artifactService.stageArtifact(stageCommand(failedLease));
    FinalizeFailureCommand failure = new FinalizeFailureCommand(
        failedLease.getPublicId(), EXEC_FP, failedLease.getFencingToken(), failedStaged.getPublicHandle(),
        LifecycleOperationResultCode.BACKUP_FAILED_EXECUTION);
    artifactService.failOperation(failure);
    artifactService.failOperation(failure);
    assertThatThrownBy(() -> artifactService.failOperation(new FinalizeFailureCommand(
            failedLease.getPublicId(), EXEC_FP, failedLease.getFencingToken(), failedStaged.getPublicHandle(),
            LifecycleOperationResultCode.BACKUP_TIMED_OUT)))
        .isInstanceOf(LifecycleControlException.class);
  }

  @Test
  void staleExecutorDenialPersistsAuditAndLeavesBusinessStateUnchanged() {
    LifecycleOperation leased = leasedOperation("idem-denial-audit");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased));
    long beforeArtifacts = artifactRepository.count();
    long beforeOps = operationRepository.count();

    assertThatThrownBy(() -> artifactService.makeArtifactAvailableAndComplete(new FinalizeAvailableCommand(
            leased.getPublicId(), EXEC_FP, leased.getFencingToken() + 1L,
            staged.getPublicHandle(), staged.getStorageKey(), availableCommand(leased, staged).metadata())))
        .isInstanceOf(LifecycleControlException.StaleFencingToken.class);

    assertThat(artifactRepository.count()).isEqualTo(beforeArtifacts);
    assertThat(operationRepository.count()).isEqualTo(beforeOps);
    assertThat(operationRepository.findByPublicId(leased.getPublicId()).orElseThrow().getState())
        .isEqualTo(LifecycleOperationState.LEASED);
    assertThat(artifactRepository.findByPublicHandle(staged.getPublicHandle()).orElseThrow().getState())
        .isEqualTo(BackupArtifactState.STAGED);
    assertThat(auditEvents(leased)).contains(LifecycleOperationAuditEventType.BACKUP_STALE_EXECUTOR_DENIED);
  }

  @Test
  void denialAuditSurvivesOuterBusinessRollback() {
    LifecycleOperation leased = leasedOperation("idem-denial-survive");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased));
    TransactionTemplate outer = new TransactionTemplate(transactionManager);

    assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
      try {
        artifactService.makeArtifactAvailableAndComplete(new FinalizeAvailableCommand(
            leased.getPublicId(), SECOND_EXEC_FP, leased.getFencingToken(),
            staged.getPublicHandle(), staged.getStorageKey(), availableCommand(leased, staged).metadata()));
      } finally {
        status.setRollbackOnly();
      }
    })).isInstanceOf(LifecycleControlException.WrongExecutor.class);

    assertThat(auditEvents(leased)).contains(LifecycleOperationAuditEventType.BACKUP_WRONG_EXECUTOR_DENIED);
    assertThat(operationRepository.findByPublicId(leased.getPublicId()).orElseThrow().getState())
        .isEqualTo(LifecycleOperationState.LEASED);
    assertThat(artifactRepository.findByPublicHandle(staged.getPublicHandle()).orElseThrow().getState())
        .isEqualTo(BackupArtifactState.STAGED);
  }

  @Test
  void concurrentStagingCreatesExactlyOneArtifact() throws Exception {
    LifecycleOperation leased = leasedOperation("idem-concurrent-stage");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger conflicts = new AtomicInteger();
    AtomicReference<BackupArtifact> winner = new AtomicReference<>();
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(pool.submit(() -> {
          ready.countDown();
          go.await(5, TimeUnit.SECONDS);
          try {
            BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased));
            winner.compareAndSet(null, staged);
          } catch (LifecycleControlException.CompletionConflict conflict) {
            conflicts.incrementAndGet();
          }
          return null;
        }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(artifactRepository.count()).isEqualTo(1);
    assertThat(winner.get()).isNotNull();
  }

  @Test
  void reLeaseOrphansPriorStagedArtifactAndAudits() {
    LifecycleOperation first = leasedOperation("idem-release-orphan");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(first));
    jdbcTemplate.update(
        "update lifecycle_operation set lease_expires_at = now() - interval '1 second' where id = ?",
        first.getId());

    LifecycleOperation second = lifecycleService.leaseNext(SECOND_EXEC_FP).orElseThrow();
    BackupArtifact orphaned = artifactRepository.findByPublicHandle(staged.getPublicHandle()).orElseThrow();
    assertThat(second.getFencingToken()).isEqualTo(first.getFencingToken() + 1);
    assertThat(orphaned.getState()).isEqualTo(BackupArtifactState.ORPHANED);
    assertThat(orphaned.getFailureCode()).isEqualTo("EXPIRED_LEASE_REPLACED");
    assertThat(auditEvents(first)).contains(LifecycleOperationAuditEventType.BACKUP_ARTIFACT_ORPHANED);

    // The re-leasing executor is NOT the audit principal of the internal orphan transition: the
    // principal is the fixed backend system releaser, and the executor that triggered the re-lease is
    // preserved only as bounded metadata (never as the principal fingerprint).
    String orphanClause = " from lifecycle_operation_audit where lifecycle_operation_id = ?"
        + " and event_type = 'BACKUP_ARTIFACT_ORPHANED'";

    assertThat(jdbcTemplate.queryForObject(
        "select principal_type" + orphanClause,
        String.class,
        first.getId()))
        .isEqualTo("SYSTEM");

    assertThat(jdbcTemplate.queryForObject(
        "select principal_fingerprint" + orphanClause,
        String.class,
        first.getId()))
        .isEqualTo(LifecycleOperationAuditor.SYSTEM_RELEASER_FINGERPRINT);

    String orphanArtifactHandle = jdbcTemplate.queryForObject(
        "select metadata ->> 'artifactHandle'" + orphanClause,
        String.class,
        first.getId());

    String orphanTriggerExecutorFingerprint = jdbcTemplate.queryForObject(
        "select metadata ->> 'triggerExecutorFingerprint'" + orphanClause,
        String.class,
        first.getId());

    assertThat(orphanArtifactHandle).isEqualTo(staged.getPublicHandle());
    assertThat(orphanTriggerExecutorFingerprint).isEqualTo(SECOND_EXEC_FP);
  }

  @Test
  void terminalArtifactRowsAreImmutableAtPostgreSql() {
    LifecycleOperation leased = leasedOperation("idem-terminal-trigger");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased));
    artifactService.makeArtifactAvailableAndComplete(availableCommand(leased, staged));

    assertThatThrownBy(() -> jdbcTemplate.update(
        "update backup_artifact set state = 'ORPHANED', failure_code = 'EXPIRED_LEASE_REPLACED', "
            + "available_at = null, encryption_algorithm = null, encryption_envelope_version = null, "
            + "encryption_key_identifier = null, postgres_server_version = null, pg_dump_version = null, "
            + "pg_restore_version = null, schema_version = null, encrypted_byte_size = null, "
            + "ciphertext_sha256 = null, archive_validated = null, archive_entry_count = null "
            + "where public_handle = ?",
        staged.getPublicHandle()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void everyTerminalColumnMutationIsIndependentlyRejectedAndRowStaysFieldEquivalent() {
    LifecycleOperation leased = leasedOperation("idem-terminal-percol");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased));
    artifactService.makeArtifactAvailableAndComplete(availableCommand(leased, staged));
    String handle = staged.getPublicHandle();
    // A second real operation supplies a *valid* alternative FK so the operation-linkage mutation fails
    // solely on the terminal-immutability trigger, not on a foreign-key violation.
    LifecycleOperation other = leasedOperation("idem-terminal-other");

    TerminalMutationBaseline baseline = new TerminalMutationBaseline(
        handle,
        snapshotArtifact(handle),
        auditRepository.count(),
        operationRepository.count(),
        leased.getPublicId(),
        operationStateOf(leased.getPublicId()),
        other.getPublicId(),
        operationStateOf(other.getPublicId()),
        sideEffectCounts());

    // Complete column matrix: EVERY column of backup_artifact (V68), including the primary key, is
    // attempted independently, each in its OWN new transaction, so a PostgreSQL statement failure aborts
    // and rolls back only that isolated transaction and the next attempt begins from a clean, non-aborted
    // transaction. Every attempt must be rejected by trg_backup_artifact_forbid_terminal_rewrite with the
    // bounded trigger error (never a downstream "current transaction is aborted" false positive). The
    // whole-row `NEW IS DISTINCT FROM OLD` trigger fires BEFORE any column CHECK/FK constraint, so even a
    // mutation to an otherwise CHECK/FK-constrained column fails on the immutability trigger first. The
    // immutable primary key `id` IS included in the contract here (not excluded): it is likewise protected
    // by the whole-row comparison, so this method genuinely covers every terminal column.
    assertTerminalUpdateRejected(baseline, "id = ?", UUID.randomUUID(), handle);
    assertTerminalUpdateRejected(baseline, "public_handle = 'ba_ffffffffffffffffffffffff'", handle);
    assertTerminalUpdateRejected(baseline, "lifecycle_operation_id = ?", other.getId(), handle);
    assertTerminalUpdateRejected(baseline, "state = 'ORPHANED'", handle);
    assertTerminalUpdateRejected(baseline, "backup_format = 'POSTGRES_PLAIN'", handle);
    assertTerminalUpdateRejected(baseline, "encryption_algorithm = null", handle);
    assertTerminalUpdateRejected(baseline, "encryption_envelope_version = 'v2'", handle);
    assertTerminalUpdateRejected(baseline, "encryption_key_identifier = 'tampered-key'", handle);
    assertTerminalUpdateRejected(
        baseline, "created_at = ?", Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")), handle);
    assertTerminalUpdateRejected(
        baseline, "updated_at = ?", Timestamp.from(Instant.parse("2100-01-01T00:00:00Z")), handle);
    assertTerminalUpdateRejected(
        baseline, "available_at = ?", Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")), handle);
    assertTerminalUpdateRejected(baseline, "postgres_server_version = '15.1'", handle);
    assertTerminalUpdateRejected(baseline, "pg_dump_version = '15.1'", handle);
    assertTerminalUpdateRejected(baseline, "pg_restore_version = '15.1'", handle);
    assertTerminalUpdateRejected(baseline, "schema_version = 'V67'", handle);
    assertTerminalUpdateRejected(baseline, "encrypted_byte_size = 999999", handle);
    assertTerminalUpdateRejected(
        baseline, "ciphertext_sha256 = 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'", handle);
    assertTerminalUpdateRejected(baseline, "archive_validated = false", handle);
    assertTerminalUpdateRejected(baseline, "archive_entry_count = 999", handle);
    assertTerminalUpdateRejected(
        baseline, "storage_key = 'lifecycle/backup/other/attempt-9/token-9/artifact.dump.enc'", handle);
    assertTerminalUpdateRejected(baseline, "execution_attempt = 999", handle);
    assertTerminalUpdateRejected(baseline, "fencing_token = 999", handle);
    assertTerminalUpdateRejected(baseline, "failure_code = 'EXPIRED_LEASE_REPLACED'", handle);
  }

  @Test
  void validStagedTransitionsStillSucceedAlongsideTerminalImmutability() {
    // Actual BackupArtifactState machine (see BackupArtifactState + BackupArtifact): the only initial
    // state is STAGED, and the sole legal transitions are STAGED -> AVAILABLE (authoritative success),
    // STAGED -> REJECTED (failure flow; the *operation* moves to FAILED while the *artifact* moves to
    // REJECTED — they are distinct enums), and STAGED -> ORPHANED (expired-lease re-lease). AVAILABLE,
    // REJECTED and ORPHANED are all terminal. There is no REJECTED-invented-for-a-test state: REJECTED is
    // produced by the real BackupArtifact.reject(...) path exercised below.

    // STAGED -> AVAILABLE (authoritative success) still succeeds.
    LifecycleOperation availableOp = leasedOperation("idem-valid-available");
    BackupArtifact availableStaged = artifactService.stageArtifact(stageCommand(availableOp));
    BackupArtifact available =
        artifactService.makeArtifactAvailableAndComplete(availableCommand(availableOp, availableStaged));
    assertThat(available.getState()).isEqualTo(BackupArtifactState.AVAILABLE);
    assertThat(operationRepository.findByPublicId(availableOp.getPublicId()).orElseThrow().getState())
        .isEqualTo(LifecycleOperationState.SUCCEEDED);

    // STAGED -> REJECTED (FAILED flow) still succeeds.
    LifecycleOperation failOp = leasedOperation("idem-valid-failed");
    BackupArtifact failStaged = artifactService.stageArtifact(stageCommand(failOp));
    LifecycleOperation failed = artifactService.failOperation(new FinalizeFailureCommand(
        failOp.getPublicId(), EXEC_FP, failOp.getFencingToken(), failStaged.getPublicHandle(),
        LifecycleOperationResultCode.BACKUP_FAILED_EXECUTION));
    assertThat(failed.getState()).isEqualTo(LifecycleOperationState.FAILED);
    assertThat(artifactRepository.findByPublicHandle(failStaged.getPublicHandle()).orElseThrow().getState())
        .isEqualTo(BackupArtifactState.REJECTED);

    // STAGED -> ORPHANED (expired-lease re-lease flow) still succeeds.
    LifecycleOperation orphanOp = leasedOperation("idem-valid-orphan");
    BackupArtifact orphanStaged = artifactService.stageArtifact(stageCommand(orphanOp));
    jdbcTemplate.update(
        "update lifecycle_operation set lease_expires_at = now() - interval '1 second' where id = ?",
        orphanOp.getId());
    lifecycleService.leaseNext(SECOND_EXEC_FP).orElseThrow();
    assertThat(artifactRepository.findByPublicHandle(orphanStaged.getPublicHandle()).orElseThrow().getState())
        .isEqualTo(BackupArtifactState.ORPHANED);
  }

  @Test
  void lifecycleOperationAuditIsAppendOnlyAtPostgreSql() {
    // request + lease already wrote durable audit rows for this operation.
    LifecycleOperation leased = leasedOperation("idem-audit-append-only");
    Long auditId = jdbcTemplate.queryForObject(
        "select id from lifecycle_operation_audit where lifecycle_operation_id = ? order by id limit 1",
        Long.class, leased.getId());
    assertThat(auditId).isNotNull();
    Map<String, Object> before = snapshotAudit(auditId);

    // The UPDATE rejection runs in its OWN isolated transaction so the failure is the append-only trigger
    // itself, not a cascaded abort. A no-op-valued UPDATE is still rejected by
    // trg_lifecycle_operation_audit_append_only.
    assertAuditMutationRejected(
        "update lifecycle_operation_audit set principal_fingerprint = principal_fingerprint where id = ?",
        auditId);
    // Clean read after the aborted UPDATE transaction: the durable row survives intact and unchanged.
    assertThat(countAuditRowsById(auditId)).isEqualTo(1);
    assertThat(reloadAuditInCleanTransaction(auditId)).isEqualTo(before);

    // The DELETE rejection runs in a SEPARATE isolated transaction, again proving the trigger, not abort.
    assertAuditMutationRejected("delete from lifecycle_operation_audit where id = ?", auditId);
    // Clean read after the aborted DELETE transaction: the durable evidence row still exists exactly once.
    assertThat(countAuditRowsById(auditId)).isEqualTo(1);
    assertThat(reloadAuditInCleanTransaction(auditId)).isEqualTo(before);
  }

  @Test
  void availableArtifactRejectsNonCanonicalToolAndSchemaVersionsAtPostgreSql() {
    LifecycleOperation leased = leasedOperation("idem-canonical-version");
    // Everything is valid except a raw pg tool banner in postgres_server_version.
    assertThatThrownBy(() -> insertAvailableWithVersions(
        handle(0x9210), leased.getId(), "PostgreSQL 16", "16.4", "16.4", "V68"))
        .isInstanceOf(DataIntegrityViolationException.class);
    // A schema_version missing the canonical 'V' prefix is likewise rejected.
    assertThatThrownBy(() -> insertAvailableWithVersions(
        handle(0x9211), leased.getId(), "16.4", "16.4", "16.4", "68"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void auditLatestWindowReturnsNewestOneHundredEvents() {
    LifecycleOperation leased = leasedOperation("idem-audit-window");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased));
    for (int i = 0; i < 110; i++) {
      jdbcTemplate.update("""
          insert into lifecycle_operation_audit (
            lifecycle_operation_id, backup_artifact_id, event_type, principal_type,
            principal_fingerprint, result_code, metadata, created_at
          ) values (?, null, 'BACKUP_EXECUTION_STARTED', 'EXECUTOR', ?, null, '{}'::jsonb, now() + (? || ' seconds')::interval)
          """, leased.getId(), EXEC_FP, i);
    }

    List<LifecycleOperationAudit> window =
        auditRepository.findTop100ByLifecycleOperationIdOrderByCreatedAtDescIdDesc(leased.getId());
    assertThat(window).hasSize(100);
    assertThat(window.get(0).getCreatedAt()).isAfterOrEqualTo(window.get(99).getCreatedAt());
    assertThat(auditRepository.findTop100ByBackupArtifactIdOrderByCreatedAtDescIdDesc(staged.getId()))
        .extracting(LifecycleOperationAudit::getEventType)
        .contains(LifecycleOperationAuditEventType.BACKUP_ARTIFACT_STAGED);
  }

  @Test
  void auditMetadataBoundsAndLatestOrderingAreEnforced() {
    LifecycleOperation leased = leasedOperation("idem-audit-bounds");
    BackupArtifact staged = artifactService.stageArtifact(stageCommand(leased));

    assertThat(auditRepository.findTop100ByLifecycleOperationIdOrderByCreatedAtDescIdDesc(leased.getId()))
        .extracting(LifecycleOperationAudit::getEventType)
        .containsExactly(
            LifecycleOperationAuditEventType.BACKUP_ARTIFACT_STAGED,
            LifecycleOperationAuditEventType.BACKUP_LEASE_ACQUIRED,
            LifecycleOperationAuditEventType.BACKUP_REQUESTED);
    assertThat(auditRepository.findTop100ByBackupArtifactIdOrderByCreatedAtDescIdDesc(staged.getId()))
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
    try {
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
    } finally {
      // Isolated migration schemas must not remain in the shared test database; unscoped
      // pg_indexes queries in other integration tests would otherwise count duplicate index names.
      jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private Flyway flywayForSchema(String schema, String target) {
    var configuration = Flyway.configure()
        .dataSource(
            LifecyclePostgresTestSupport.jdbcUrl(),
            LifecyclePostgresTestSupport.username(),
            LifecyclePostgresTestSupport.password())
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

  private StageArtifactCommand stageCommand(LifecycleOperation operation) {
    return new StageArtifactCommand(
        operation.getPublicId(), EXEC_FP, operation.getFencingToken());
  }

  private FinalizeAvailableCommand availableCommand(LifecycleOperation operation, BackupArtifact artifact) {
    return new FinalizeAvailableCommand(
        operation.getPublicId(),
        EXEC_FP,
        operation.getFencingToken(),
        artifact.getPublicHandle(),
        artifact.getStorageKey(),
        new AvailableMetadata(
            "AES-256-GCM",
            "v1",
            "backup-key-2026-07",
            "16.4",
            "16.4",
            "16.4",
            "V68",
            128L,
            SHA,
            true,
            12));
  }

  private String handle(int value) {
    return "ba_" + String.format("%024x", value);
  }

  private Map<String, Object> snapshotArtifact(String publicHandle) {
    return jdbcTemplate.queryForMap("select * from backup_artifact where public_handle = ?", publicHandle);
  }

  private void assertTerminalUpdateRejected(TerminalMutationBaseline baseline, String setFragment, Object... args) {
    // Each rejected mutation runs in its OWN new transaction (PROPAGATION_REQUIRES_NEW). On the PostgreSQL
    // statement failure that isolated transaction is aborted and rolled back, so the following attempt
    // starts from a clean transaction - never a false-positive "current transaction is aborted". Each
    // attempt must surface the bounded trigger error (SQL state check_violation), never a silent no-op or
    // partial write.
    Throwable thrown = catchThrowable(() -> isolatedTransaction().executeWithoutResult(status -> jdbcTemplate.update(
        "update backup_artifact set " + setFragment + " where public_handle = ?", args)));
    assertThat(thrown)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("BACKUP_ARTIFACT_TERMINAL_IMMUTABLE");
    assertThat(deepestSqlState(thrown)).isEqualTo("23514");

    // Clean transaction proof after EVERY failed UPDATE: the row and adjacent business evidence remain
    // unchanged, and no outbox/connector/storage/process side-effect row was introduced.
    assertThat(reloadArtifactInCleanTransaction(baseline.publicHandle())).isEqualTo(baseline.artifact());
    assertThat(isolatedCount("lifecycle_operation_audit")).isEqualTo(baseline.auditCount());
    assertThat(isolatedCount("lifecycle_operation")).isEqualTo(baseline.operationCount());
    assertThat(operationStateOf(baseline.leasedPublicId())).isEqualTo(baseline.leasedState());
    assertThat(operationStateOf(baseline.otherPublicId())).isEqualTo(baseline.otherState());
    assertThat(sideEffectCounts()).isEqualTo(baseline.sideEffectCounts());
  }

  private void assertAuditMutationRejected(String sql, Object... args) {
    Throwable thrown = catchThrowable(
        () -> isolatedTransaction().executeWithoutResult(status -> jdbcTemplate.update(sql, args)));
    assertThat(thrown)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("LIFECYCLE_OPERATION_AUDIT_APPEND_ONLY");
    assertThat(deepestSqlState(thrown)).isEqualTo("23514");
  }

  private TransactionTemplate isolatedTransaction() {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  private String operationStateOf(String publicId) {
    return operationRepository.findByPublicId(publicId).orElseThrow().getState().name();
  }

  private Map<String, Object> reloadArtifactInCleanTransaction(String publicHandle) {
    // A fresh REQUIRES_NEW transaction guarantees the comparison reads committed state, independent of any
    // prior rolled-back mutation transaction.
    return isolatedTransaction().execute(status -> snapshotArtifact(publicHandle));
  }

  private Map<String, Object> snapshotAudit(Long auditId) {
    return jdbcTemplate.queryForMap("select * from lifecycle_operation_audit where id = ?", auditId);
  }

  private Map<String, Object> reloadAuditInCleanTransaction(Long auditId) {
    return isolatedTransaction().execute(status -> snapshotAudit(auditId));
  }

  private long isolatedCount(String table) {
    Long count = isolatedTransaction().execute(status ->
        jdbcTemplate.queryForObject("select count(*) from " + table, Long.class));
    return count == null ? 0L : count;
  }

  private Map<String, Long> sideEffectCounts() {
    return Map.of(
        "outbox_event", isolatedCount("outbox_event"),
        "connector_command", isolatedCount("connector_command"),
        "object_storage_record", isolatedCount("object_storage_record"),
        "processing_job", isolatedCount("processing_job"));
  }

  private String deepestSqlState(Throwable thrown) {
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

  private record TerminalMutationBaseline(
      String publicHandle,
      Map<String, Object> artifact,
      long auditCount,
      long operationCount,
      String leasedPublicId,
      String leasedState,
      String otherPublicId,
      String otherState,
      Map<String, Long> sideEffectCounts) {}

  private int countAuditRowsById(Long auditId) {
    Integer count = isolatedTransaction().execute(status -> jdbcTemplate.queryForObject(
        "select count(*) from lifecycle_operation_audit where id = ?", Integer.class, auditId));
    return count == null ? 0 : count;
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
        where table_schema = current_schema()
          and table_name in ('backup_artifact', 'lifecycle_operation_audit')
        """, String.class);
  }

  private List<String> indexNames() {
    return jdbcTemplate.queryForList("""
        select indexname
        from pg_indexes
        where schemaname = current_schema()
          and tablename in ('backup_artifact', 'lifecycle_operation_audit')
        """, String.class);
  }

  private List<LifecycleOperationAuditEventType> auditEvents(LifecycleOperation operation) {
    return auditRepository.findTop100ByLifecycleOperationIdOrderByCreatedAtDescIdDesc(operation.getId())
        .stream()
        .map(LifecycleOperationAudit::getEventType)
        .toList();
  }

  private void assertRepositoryHasNoUpdateOrDeleteApi(Class<?> repositoryType) {
    assertThat(Arrays.stream(repositoryType.getMethods()).map(Method::getName))
        .noneMatch(name -> name.startsWith("delete") || name.startsWith("update"));
  }

  private void insertStaged(String publicHandle, UUID operationId, String storageKey) {
    insertStaged(publicHandle, operationId, storageKey, 1, 1L);
  }

  private void insertStaged(
      String publicHandle, UUID operationId, String storageKey, int executionAttempt, long fencingToken) {
    jdbcTemplate.update("""
        insert into backup_artifact (
          public_handle, lifecycle_operation_id, state, backup_format, created_at, updated_at,
          storage_key, execution_attempt, fencing_token
        ) values (?, ?, 'STAGED', 'POSTGRES_CUSTOM', now(), now(), ?, ?, ?)
        """, publicHandle, operationId, storageKey, executionAttempt, fencingToken);
  }

  private void insertAvailableWithVersions(
      String publicHandle,
      UUID operationId,
      String postgresServerVersion,
      String pgDumpVersion,
      String pgRestoreVersion,
      String schemaVersion) {
    // Valid AVAILABLE row except for the caller-provided version fields, isolating the failure cause to
    // the canonical pg-tool / schema version CHECK constraints.
    jdbcTemplate.update("""
        insert into backup_artifact (
          public_handle, lifecycle_operation_id, state, backup_format, encryption_algorithm,
          encryption_envelope_version, encryption_key_identifier, created_at, updated_at, available_at,
          postgres_server_version, pg_dump_version, pg_restore_version, schema_version,
          encrypted_byte_size, ciphertext_sha256, archive_validated, archive_entry_count, storage_key,
          execution_attempt, fencing_token
        ) values (?, ?, 'AVAILABLE', 'POSTGRES_CUSTOM', 'AES-256-GCM', 'v1', 'backup-key-2026-07',
          now(), now(), now(), ?, ?, ?, ?, 128, ?, true, 12,
          'lifecycle/backup/op/attempt-1/token-1/artifact.dump.enc', 1, 1)
        """,
        publicHandle,
        operationId,
        postgresServerVersion,
        pgDumpVersion,
        pgRestoreVersion,
        schemaVersion,
        SHA);
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
    insertAvailable(publicHandle, operationId, storageKey, digest, encryptedByteSize, archiveValidated,
        encryptionAlgorithm, envelopeVersion, keyIdentifier, availableAt, 1, 1L);
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
      Instant availableAt,
      int executionAttempt,
      long fencingToken) {
    Timestamp timestamp = availableAt == null ? null : Timestamp.from(availableAt);
    jdbcTemplate.update("""
        insert into backup_artifact (
          public_handle, lifecycle_operation_id, state, backup_format, encryption_algorithm,
          encryption_envelope_version, encryption_key_identifier, created_at, updated_at, available_at,
          postgres_server_version, pg_dump_version, pg_restore_version, schema_version,
          encrypted_byte_size, ciphertext_sha256, archive_validated, archive_entry_count, storage_key,
          execution_attempt, fencing_token
        ) values (?, ?, 'AVAILABLE', 'POSTGRES_CUSTOM', ?, ?, ?, coalesce(?, now()), coalesce(?, now()), ?,
          '16.4', '16.4', '16.4', 'V68', ?, ?, ?, 1, ?, ?, ?)
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
        storageKey,
        executionAttempt,
        fencingToken);
  }
}
