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
}
