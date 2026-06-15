package com.orderpilot.domain.journey.events;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-23 — tenant-scoped, bounded checkpoint queries only. Every finder is tenant-isolated; there is no
 * cross-tenant lookup and no unbounded scan (lists are always paged).
 */
public interface OrderJourneyProjectionCheckpointRepository
    extends JpaRepository<OrderJourneyProjectionCheckpoint, UUID> {
  Optional<OrderJourneyProjectionCheckpoint> findByTenantIdAndProjectorNameAndEventId(
      UUID tenantId, String projectorName, UUID eventId);

  long countByTenantIdAndStatus(UUID tenantId, JourneyProjectionCheckpointStatus status);

  List<OrderJourneyProjectionCheckpoint> findByTenantIdAndStatusOrderByUpdatedAtDesc(
      UUID tenantId, JourneyProjectionCheckpointStatus status, Pageable pageable);

  Optional<OrderJourneyProjectionCheckpoint> findFirstByTenantIdAndProjectorNameOrderByUpdatedAtDesc(
      UUID tenantId, String projectorName);
}
