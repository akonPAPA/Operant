package com.orderpilot.domain.control;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface LifecycleOperationAuditRepository extends Repository<LifecycleOperationAudit, Long> {
  LifecycleOperationAudit save(LifecycleOperationAudit audit);

  List<LifecycleOperationAudit> findTop100ByLifecycleOperationIdOrderByCreatedAtDescIdDesc(
      UUID lifecycleOperationId);

  List<LifecycleOperationAudit> findTop100ByBackupArtifactIdOrderByCreatedAtDescIdDesc(
      UUID backupArtifactId);

  long count();
}