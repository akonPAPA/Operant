package com.orderpilot.domain.control;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;

public interface BackupArtifactRepository extends Repository<BackupArtifact, UUID> {
  BackupArtifact save(BackupArtifact artifact);

  Optional<BackupArtifact> findById(UUID id);

  Optional<BackupArtifact> findByPublicHandle(String publicHandle);

  List<BackupArtifact> findByLifecycleOperationId(UUID lifecycleOperationId);

  Optional<BackupArtifact> findByLifecycleOperationIdAndState(
      UUID lifecycleOperationId, BackupArtifactState state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<BackupArtifact> findWithLockById(UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<BackupArtifact> findWithLockByPublicHandle(String publicHandle);

  long count();
}
