package com.orderpilot.domain.trust.analytics;

import com.orderpilot.domain.trust.TrustRiskLevel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-17E Trust Analytics Read Models. Tenant-scoped, bounded queries only — the caller always
 * supplies a clamped {@link Pageable}. Every finder is tenant-isolated.
 */
public interface TrustReviewQueueViewRepository extends JpaRepository<TrustReviewQueueView, UUID> {
  Optional<TrustReviewQueueView> findByTenantIdAndTrustRiskDecisionId(UUID tenantId, UUID trustRiskDecisionId);

  List<TrustReviewQueueView> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

  List<TrustReviewQueueView> findByTenantIdAndRiskLevelOrderByCreatedAtDesc(
      UUID tenantId, TrustRiskLevel riskLevel, Pageable pageable);

  List<TrustReviewQueueView> findByTenantIdAndApprovalStatusOrderByCreatedAtDesc(
      UUID tenantId, String approvalStatus, Pageable pageable);

  List<TrustReviewQueueView> findByTenantIdAndBlockingOrderByCreatedAtDesc(
      UUID tenantId, boolean blocking, Pageable pageable);

  List<TrustReviewQueueView> findByTenantIdAndRiskLevelAndBlockingOrderByCreatedAtDesc(
      UUID tenantId, TrustRiskLevel riskLevel, boolean blocking, Pageable pageable);
}
