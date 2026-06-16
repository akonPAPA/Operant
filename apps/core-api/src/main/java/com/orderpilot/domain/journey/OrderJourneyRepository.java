package com.orderpilot.domain.journey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** OP-CAP-22 — bounded, tenant-scoped reads/counts for order journeys. No unbounded list methods. */
public interface OrderJourneyRepository extends JpaRepository<OrderJourney, UUID> {
  Optional<OrderJourney> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<OrderJourney> findByTenantIdAndSourceTypeAndSourceId(UUID tenantId, JourneySourceType sourceType, UUID sourceId);
  List<OrderJourney> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId, Pageable pageable);

  // Attention queue: blocked OR high-risk journeys, tenant-scoped, bounded by Pageable.
  @Query("select j from OrderJourney j where j.tenantId = :tenantId and (j.blocked = true or j.riskLevel in :riskLevels) order by j.updatedAt desc")
  List<OrderJourney> findAttention(@Param("tenantId") UUID tenantId, @Param("riskLevels") List<String> riskLevels, Pageable pageable);

  @Query("select count(j) from OrderJourney j where j.tenantId = :tenantId and (j.blocked = true or j.riskLevel in :riskLevels)")
  long countAttention(@Param("tenantId") UUID tenantId, @Param("riskLevels") List<String> riskLevels);

  long countByTenantId(UUID tenantId);
  long countByTenantIdAndBlockedTrue(UUID tenantId);
}
