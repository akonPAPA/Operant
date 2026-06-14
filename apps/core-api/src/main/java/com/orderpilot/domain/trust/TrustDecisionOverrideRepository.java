package com.orderpilot.domain.trust;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-17D Trust Risk Decision Engine. Tenant-scoped, append-only override evidence.
 */
public interface TrustDecisionOverrideRepository extends JpaRepository<TrustDecisionOverride, UUID> {
  List<TrustDecisionOverride> findByTenantIdAndTrustRiskDecisionIdOrderByOverriddenAtDesc(
      UUID tenantId, UUID trustRiskDecisionId);
}
