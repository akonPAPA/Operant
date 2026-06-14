package com.orderpilot.domain.trust;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-17D Trust Risk Decision Engine. Tenant-scoped, bounded approval-requirement queries.
 */
public interface TrustApprovalRequirementRepository extends JpaRepository<TrustApprovalRequirement, UUID> {
  List<TrustApprovalRequirement> findByTenantIdAndTrustRiskDecisionIdOrderByCreatedAtAsc(
      UUID tenantId, UUID trustRiskDecisionId);

  List<TrustApprovalRequirement> findByTenantIdAndTrustRiskDecisionIdAndStatusOrderByCreatedAtAsc(
      UUID tenantId, UUID trustRiskDecisionId, TrustApprovalStatus status);

  List<TrustApprovalRequirement> findByTenantIdAndStatusOrderByCreatedAtDesc(
      UUID tenantId, TrustApprovalStatus status, Pageable pageable);
}
