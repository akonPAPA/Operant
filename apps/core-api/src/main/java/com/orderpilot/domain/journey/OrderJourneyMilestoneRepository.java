package com.orderpilot.domain.journey;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** OP-CAP-22 — milestones for a journey, tenant-scoped, ordered by canonical sort order. */
public interface OrderJourneyMilestoneRepository extends JpaRepository<OrderJourneyMilestone, UUID> {
  List<OrderJourneyMilestone> findByTenantIdAndJourneyIdOrderBySortOrderAsc(UUID tenantId, UUID journeyId);
  void deleteByTenantIdAndJourneyId(UUID tenantId, UUID journeyId);
}
