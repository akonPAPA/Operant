package com.orderpilot.domain.journey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-46C — resolution and tenant-scoped management for secure tracking links. Resolution is keyed
 * on the token hash only; tenant/journey scope is then read from the resolved row (never trusted from
 * the request).
 */
public interface OrderJourneyTrackingLinkRepository extends JpaRepository<OrderJourneyTrackingLink, UUID> {
  Optional<OrderJourneyTrackingLink> findByTokenHash(String tokenHash);

  /**
   * OP-CAP-46G — operator-side lookup for revocation. Keyed on the internal link id AND both scope
   * columns so a link can only ever be found (and revoked) within its own tenant and journey; a
   * cross-tenant or cross-journey id yields empty. The raw token is never involved in revocation.
   */
  Optional<OrderJourneyTrackingLink> findByIdAndTenantIdAndJourneyId(UUID id, UUID tenantId, UUID journeyId);

  /**
   * OP-CAP-46H — operator registry listing: every link for one journey within the operator's tenant,
   * newest first. Tenant- AND journey-scoped so a cross-tenant or cross-journey id can never surface
   * another scope's links. Backed by the existing {@code (tenant_id, journey_id, created_at DESC)}
   * index from V60. The raw token is never involved; only safe lifecycle metadata is mapped out.
   */
  List<OrderJourneyTrackingLink> findByTenantIdAndJourneyIdOrderByCreatedAtDesc(UUID tenantId, UUID journeyId);
}
