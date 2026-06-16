package com.orderpilot.domain.journey;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** OP-CAP-22 — recent journey events, tenant-scoped, bounded. */
public interface OrderJourneyEventRepository extends JpaRepository<OrderJourneyEvent, UUID> {
  List<OrderJourneyEvent> findByTenantIdAndJourneyIdOrderByOccurredAtDesc(UUID tenantId, UUID journeyId, Pageable pageable);
}
