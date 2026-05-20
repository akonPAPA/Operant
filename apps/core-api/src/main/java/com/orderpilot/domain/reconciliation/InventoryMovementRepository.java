package com.orderpilot.domain.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, UUID> {
  List<InventoryMovement> findByTenantIdAndProductIdAndLocationIdAndOccurredAtLessThanEqualOrderByOccurredAtAsc(UUID tenantId, UUID productId, UUID locationId, Instant occurredAt);
  InventoryMovement findFirstByTenantIdAndProductIdAndLocationIdAndMovementTypeOrderByOccurredAtDesc(UUID tenantId, UUID productId, UUID locationId, InventoryMovementType movementType);
  boolean existsByTenantIdAndSourceTypeAndSourceReference(UUID tenantId, String sourceType, String sourceReference);
  long countByTenantId(UUID tenantId);
}
