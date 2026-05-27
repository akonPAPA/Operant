package com.orderpilot.domain.vehicle;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleModelRepository extends JpaRepository<VehicleModel, UUID> {
  List<VehicleModel> findByTenantIdAndVehicleMakeId(UUID tenantId, UUID vehicleMakeId);
}