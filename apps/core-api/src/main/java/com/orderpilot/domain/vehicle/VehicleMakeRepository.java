package com.orderpilot.domain.vehicle;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleMakeRepository extends JpaRepository<VehicleMake, UUID> {
  Optional<VehicleMake> findByTenantIdAndCode(UUID tenantId, String code);
}