package com.orderpilot.domain.journey;

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
}
