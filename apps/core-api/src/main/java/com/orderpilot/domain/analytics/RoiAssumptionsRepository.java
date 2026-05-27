package com.orderpilot.domain.analytics;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoiAssumptionsRepository extends JpaRepository<RoiAssumptions, UUID> {
  Optional<RoiAssumptions> findByTenantId(UUID tenantId);
}
