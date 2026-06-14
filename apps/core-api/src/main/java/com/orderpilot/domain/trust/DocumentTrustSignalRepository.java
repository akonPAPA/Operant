package com.orderpilot.domain.trust;

import com.orderpilot.domain.trust.analytics.DocumentAnomalyAggregate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentTrustSignalRepository extends JpaRepository<DocumentTrustSignal, UUID> {
  List<DocumentTrustSignal> findByTenantIdAndTrustRunIdOrderByCreatedAtAsc(UUID tenantId, UUID trustRunId);

  /**
   * OP-CAP-17E Trust Analytics Read Models — bounded group-by aggregation of trust signals for one
   * tenant within a half-open period window. At most one row per (signal code, severity); never an
   * unbounded per-request scan.
   */
  @Query("select s.signalCode as signalCode, s.severity as severity, "
      + "count(s) as occurrences, max(s.createdAt) as latestSeenAt "
      + "from DocumentTrustSignal s "
      + "where s.tenantId = :tenantId and s.createdAt >= :start and s.createdAt < :end "
      + "group by s.signalCode, s.severity")
  List<DocumentAnomalyAggregate> aggregateAnomaliesByPeriod(
      @Param("tenantId") UUID tenantId, @Param("start") Instant start, @Param("end") Instant end);
}
