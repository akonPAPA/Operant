package com.orderpilot.domain.integration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, UUID> {
  Optional<ChangeRequest> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<ChangeRequest> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
  Optional<ChangeRequest> findByTenantIdAndPayloadSnapshotId(UUID tenantId, UUID payloadSnapshotId);
  List<ChangeRequest> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
