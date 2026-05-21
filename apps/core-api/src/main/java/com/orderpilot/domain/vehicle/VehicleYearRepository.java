package com.orderpilot.domain.vehicle;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleYearRepository extends JpaRepository<VehicleYear, UUID> {
  List<VehicleYear> findByTenantIdAndVehicleModelId(UUID tenantId, UUID vehicleModelId);
}