package com.orderpilot.domain.pilot;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HumanCorrectionRepository extends JpaRepository<HumanCorrection, UUID> {
  List<HumanCorrection> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  List<HumanCorrection> findByTenantIdAndShadowRunIdOrderByCreatedAtDesc(UUID tenantId, UUID shadowRunId);
  long countByTenantId(UUID tenantId);
}
