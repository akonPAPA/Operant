package com.orderpilot.domain.product;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OEMReferenceRepository extends JpaRepository<OEMReference, UUID> {
  List<OEMReference> findByTenantIdAndNormalizedOemCodeAndActiveTrue(UUID tenantId, String normalizedOemCode);
}
