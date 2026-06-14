package com.orderpilot.domain.trust;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-17D Trust Risk Decision Engine. Tenant-scoped contribution evidence, bounded by decision id.
 */
public interface TrustRiskSignalContributionRepository extends JpaRepository<TrustRiskSignalContribution, UUID> {
  List<TrustRiskSignalContribution> findByTenantIdAndTrustRiskDecisionIdOrderByCreatedAtAsc(
      UUID tenantId, UUID trustRiskDecisionId);

  long countByTenantIdAndTrustRiskDecisionId(UUID tenantId, UUID trustRiskDecisionId);
}
