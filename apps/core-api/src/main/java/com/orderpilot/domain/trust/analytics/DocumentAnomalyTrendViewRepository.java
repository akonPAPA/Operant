package com.orderpilot.domain.trust.analytics;

import com.orderpilot.domain.trust.TrustSignalCode;
import com.orderpilot.domain.trust.TrustSignalSeverity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-17E Trust Analytics Read Models. Tenant-scoped, bounded queries only. Trend rows are rebuilt
 * per period with a delete-then-insert so a rebuild is idempotent (no appended duplicates).
 */
public interface DocumentAnomalyTrendViewRepository extends JpaRepository<DocumentAnomalyTrendView, UUID> {
  List<DocumentAnomalyTrendView> findByTenantIdAndPeriodKey(UUID tenantId, String periodKey);

  List<DocumentAnomalyTrendView> findByTenantIdAndPeriodKeyOrderBySignalCodeAscSeverityDesc(
      UUID tenantId, String periodKey);

  List<DocumentAnomalyTrendView> findByTenantIdAndPeriodKeyBetweenOrderByPeriodKeyAscSignalCodeAsc(
      UUID tenantId, String fromPeriodKey, String toPeriodKey, Pageable pageable);

  List<DocumentAnomalyTrendView> findByTenantIdAndSignalCodeAndPeriodKeyBetweenOrderByPeriodKeyAsc(
      UUID tenantId, TrustSignalCode signalCode, String fromPeriodKey, String toPeriodKey, Pageable pageable);

  List<DocumentAnomalyTrendView> findByTenantIdAndSeverityAndPeriodKeyBetweenOrderByPeriodKeyAsc(
      UUID tenantId, TrustSignalSeverity severity, String fromPeriodKey, String toPeriodKey, Pageable pageable);
}
