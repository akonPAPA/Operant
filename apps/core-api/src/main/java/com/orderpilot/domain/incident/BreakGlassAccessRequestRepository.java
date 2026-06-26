package com.orderpilot.domain.incident;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-53 — tenant-scoped storage/listing for break-glass access requests.
 */
public interface BreakGlassAccessRequestRepository extends JpaRepository<BreakGlassAccessRequest, UUID> {
  /** Tenant-scoped lookup: a break-glass request can only be found (and acted on) within its own tenant. */
  Optional<BreakGlassAccessRequest> findByIdAndTenantId(UUID id, UUID tenantId);

  List<BreakGlassAccessRequest> findByTenantIdOrderByRequestedAtDesc(UUID tenantId);

  List<BreakGlassAccessRequest> findByIncidentIdOrderByRequestedAtDesc(UUID incidentId);
}
