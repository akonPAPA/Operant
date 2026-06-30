package com.orderpilot.domain.incident;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-53 — tenant-scoped storage/listing for break-glass access requests.
 */
public interface BreakGlassAccessRequestRepository extends JpaRepository<BreakGlassAccessRequest, UUID> {
  /** Tenant-scoped lookup: a break-glass request can only be found (and acted on) within its own tenant. */
  Optional<BreakGlassAccessRequest> findByIdAndTenantId(UUID id, UUID tenantId);

  List<BreakGlassAccessRequest> findByTenantIdOrderByRequestedAtDesc(UUID tenantId);

  /**
   * Tenant-and-incident-scoped lookup. Incident ids alone must never reveal break-glass requests.
   */
  List<BreakGlassAccessRequest> findByTenantIdAndIncidentIdOrderByRequestedAtDesc(
      UUID tenantId, UUID incidentId);

  // OP-CAP-55 — read-only operations visibility (bounded queries, indexed by idx_break_glass_tenant_status).
  long countByTenantIdAndStatus(UUID tenantId, BreakGlassStatus status);

  long countByTenantIdAndStatusAndExpiresAtAfter(UUID tenantId, BreakGlassStatus status, Instant now);

  Optional<BreakGlassAccessRequest> findFirstByTenantIdOrderByRequestedAtDesc(UUID tenantId);

  List<BreakGlassAccessRequest> findByTenantIdOrderByRequestedAtDesc(UUID tenantId, Pageable pageable);
}
