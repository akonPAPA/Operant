package com.orderpilot.domain.inventory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventorySnapshotRepository extends JpaRepository<InventorySnapshot, UUID> {
  List<InventorySnapshot> findTop50ByTenantIdAndProductIdAndLocationIdOrderByCapturedAtDesc(UUID tenantId, UUID productId, UUID locationId);
  List<InventorySnapshot> findTop50ByTenantIdAndProductIdOrderByCapturedAtDesc(UUID tenantId, UUID productId);
  List<InventorySnapshot> findTop50ByTenantIdOrderByCapturedAtDesc(UUID tenantId);
}