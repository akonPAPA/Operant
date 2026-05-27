package com.orderpilot.domain.vehicle;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleConfigurationRepository extends JpaRepository<VehicleConfiguration, UUID> {
  List<VehicleConfiguration> findByTenantIdAndVehicleYearId(UUID tenantId, UUID vehicleYearId);
}