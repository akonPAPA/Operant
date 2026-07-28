package com.orderpilot.domain.control;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface BackupArtifactRepository extends Repository<BackupArtifact, UUID> {
  BackupArtifact save(BackupArtifact artifact);

  Optional<BackupArtifact> findById(UUID id);

  Optional<BackupArtifact> findByPublicHandle(String publicHandle);

  /**
   * Bounded lookup for the single authoritative AVAILABLE artifact of an operation. Uniqueness is
   * enforced by {@code ux_backup_artifact_one_available_per_operation}.
   */
  Optional<BackupArtifact> findByLifecycleOperationIdAndState(
      UUID lifecycleOperationId, BackupArtifactState state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<BackupArtifact> findWithLockById(UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<BackupArtifact> findWithLockByLifecycleOperationIdAndExecutionAttemptAndFencingToken(
      UUID lifecycleOperationId, Integer executionAttempt, Long fencingToken);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<BackupArtifact> findWithLockByLifecycleOperationIdAndExecutionAttemptAndFencingTokenAndPublicHandle(
      UUID lifecycleOperationId, Integer executionAttempt, Long fencingToken, String publicHandle);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select a
      from BackupArtifact a
      where a.lifecycleOperation.id = :operationId
        and a.state = com.orderpilot.domain.control.BackupArtifactState.STAGED
      order by a.createdAt asc, a.id asc
      """)
  List<BackupArtifact> findStagedWithLockByLifecycleOperationId(
      @Param("operationId") UUID operationId, Pageable pageable);

  long count();
}
