package com.orderpilot.domain.usage;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface UsageCounterRepository extends JpaRepository<UsageCounter, UUID> {

  Optional<UsageCounter> findByTenantIdAndMetricTypeAndPeriodKey(
      UUID tenantId, UsageMetricType metricType, String periodKey);

  /**
   * Pessimistic write lock for the increment path — serializes concurrent recordings against the
   * same {@code (tenant, metric, period)} counter so accumulation stays correct.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<UsageCounter> findWithLockByTenantIdAndMetricTypeAndPeriodKey(
      UUID tenantId, UsageMetricType metricType, String periodKey);
}
