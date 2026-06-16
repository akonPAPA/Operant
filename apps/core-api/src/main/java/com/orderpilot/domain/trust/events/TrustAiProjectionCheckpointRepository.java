package com.orderpilot.domain.trust.events;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-18 Trust/AI Event Projector Runtime. Tenant-scoped, bounded queries only.
 */
public interface TrustAiProjectionCheckpointRepository
    extends JpaRepository<TrustAiProjectionCheckpoint, UUID> {
  Optional<TrustAiProjectionCheckpoint> findByTenantIdAndProjectorNameAndEventId(
      UUID tenantId, String projectorName, UUID eventId);

  List<TrustAiProjectionCheckpoint> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId, Pageable pageable);

  List<TrustAiProjectionCheckpoint> findByTenantIdAndProjectorNameOrderByUpdatedAtDesc(
      UUID tenantId, String projectorName, Pageable pageable);

  List<TrustAiProjectionCheckpoint> findByTenantIdAndStatusOrderByUpdatedAtDesc(
      UUID tenantId, TrustAiProjectionStatus status, Pageable pageable);

  List<TrustAiProjectionCheckpoint> findByTenantIdAndProjectorNameAndStatusOrderByUpdatedAtDesc(
      UUID tenantId, String projectorName, TrustAiProjectionStatus status, Pageable pageable);
}
