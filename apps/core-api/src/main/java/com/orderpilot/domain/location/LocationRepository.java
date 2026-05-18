package com.orderpilot.domain.location;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, UUID> {
  Optional<Location> findByTenantIdAndCode(UUID tenantId, String code);
}