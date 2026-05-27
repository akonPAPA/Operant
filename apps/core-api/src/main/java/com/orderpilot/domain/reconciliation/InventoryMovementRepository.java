package com.orderpilot.domain.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, UUID> {
  interface ProductLocationPair {
    UUID getProductId();
    UUID getLocationId();
  }

  List<InventoryMovement> findByTenantIdAndProductIdAndLocationIdAndOccurredAtLessThanEqualOrderByOccurredAtAsc(UUID tenantId, UUID productId, UUID locationId, Instant occurredAt);
  List<InventoryMovement> findTop100ByTenantIdAndProductIdOrderByOccurredAtDesc(UUID tenantId, UUID productId);
  InventoryMovement findFirstByTenantIdAndProductIdAndLocationIdAndMovementTypeOrderByOccurredAtDesc(UUID tenantId, UUID productId, UUID locationId, InventoryMovementType movementType);
  boolean existsByTenantIdAndSourceTypeAndSourceReference(UUID tenantId, String sourceType, String sourceReference);
  long countByTenantId(UUID tenantId);
  long countByTenantIdAndMovementType(UUID tenantId, InventoryMovementType movementType);

  @Query("select distinct m.productId as productId, m.locationId as locationId from InventoryMovement m where m.tenantId = :tenantId and m.movementType = :movementType")
  List<ProductLocationPair> findDistinctProductLocationsByTenantIdAndMovementType(UUID tenantId, InventoryMovementType movementType);
}
