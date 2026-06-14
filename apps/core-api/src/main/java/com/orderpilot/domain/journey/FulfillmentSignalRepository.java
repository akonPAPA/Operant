package com.orderpilot.domain.journey;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** OP-CAP-22 — fulfillment signals for a journey, tenant-scoped, bounded. */
public interface FulfillmentSignalRepository extends JpaRepository<FulfillmentSignal, UUID> {
  List<FulfillmentSignal> findByTenantIdAndJourneyIdOrderByReceivedAtDesc(UUID tenantId, UUID journeyId, Pageable pageable);
  List<FulfillmentSignal> findByTenantIdAndJourneyIdOrderByReceivedAtAsc(UUID tenantId, UUID journeyId);
}
