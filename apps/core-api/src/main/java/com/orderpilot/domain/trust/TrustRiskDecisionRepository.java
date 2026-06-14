package com.orderpilot.domain.trust;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-17D Trust Risk Decision Engine.
 *
 * Tenant-scoped, bounded queries only. The caller always supplies a clamped {@link Pageable}; there is
 * no unbounded per-request scan. Every finder is tenant-isolated.
 */
public interface TrustRiskDecisionRepository extends JpaRepository<TrustRiskDecision, UUID> {
  Optional<TrustRiskDecision> findByIdAndTenantId(UUID id, UUID tenantId);

  /** Latest active decision for a subject (idempotency + "current decision" lookups). */
  Optional<TrustRiskDecision> findFirstByTenantIdAndSubjectTypeAndSubjectIdAndStatusOrderByCreatedAtDesc(
      UUID tenantId, String subjectType, UUID subjectId, TrustRiskDecisionStatus status);

  /** Idempotency: collapse repeat evaluations carrying the same caller token onto the active decision. */
  Optional<TrustRiskDecision> findFirstByTenantIdAndIdempotencyKeyAndStatusOrderByCreatedAtDesc(
      UUID tenantId, String idempotencyKey, TrustRiskDecisionStatus status);

  List<TrustRiskDecision> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

  List<TrustRiskDecision> findByTenantIdAndSubjectTypeAndSubjectIdOrderByCreatedAtDesc(
      UUID tenantId, String subjectType, UUID subjectId, Pageable pageable);

  List<TrustRiskDecision> findByTenantIdAndRiskLevelOrderByCreatedAtDesc(
      UUID tenantId, TrustRiskLevel riskLevel, Pageable pageable);

  List<TrustRiskDecision> findByTenantIdAndStatusOrderByCreatedAtDesc(
      UUID tenantId, TrustRiskDecisionStatus status, Pageable pageable);

  List<TrustRiskDecision> findByTenantIdAndRiskLevelAndStatusOrderByCreatedAtDesc(
      UUID tenantId, TrustRiskLevel riskLevel, TrustRiskDecisionStatus status, Pageable pageable);
}
