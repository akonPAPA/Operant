package com.orderpilot.domain.trust;

import com.orderpilot.domain.trust.analytics.TrustRiskDistributionAggregate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  // --------------------------------------------------------------------------------------------------
  // OP-CAP-17E Trust Analytics Read Models — bounded finders/aggregates that drive projection rebuilds.
  // All are tenant-scoped; none performs an unbounded per-request scan.
  // --------------------------------------------------------------------------------------------------

  /** Counterparty dashboard: current count of decisions at a risk level in a given status. */
  long countByTenantIdAndCounterpartyIdAndRiskLevelAndStatus(
      UUID tenantId, UUID counterpartyId, TrustRiskLevel riskLevel, TrustRiskDecisionStatus status);

  /** Counterparty dashboard: most recent decision (any status) at a risk level for a counterparty. */
  Optional<TrustRiskDecision> findFirstByTenantIdAndCounterpartyIdAndRiskLevelOrderByCreatedAtDesc(
      UUID tenantId, UUID counterpartyId, TrustRiskLevel riskLevel);

  /** Outstanding debt: latest decision in a status linked to a payment obligation. */
  Optional<TrustRiskDecision> findFirstByTenantIdAndPaymentObligationIdAndStatusOrderByCreatedAtDesc(
      UUID tenantId, UUID paymentObligationId, TrustRiskDecisionStatus status);

  /**
   * Trust risk distribution: bounded single-row conditional aggregate for one tenant + period. The
   * decision table is scanned at most once per rebuild, never per read request.
   */
  @Query("select "
      + "coalesce(sum(case when d.riskLevel = com.orderpilot.domain.trust.TrustRiskLevel.LOW then 1 else 0 end), 0) as lowCount, "
      + "coalesce(sum(case when d.riskLevel = com.orderpilot.domain.trust.TrustRiskLevel.MEDIUM then 1 else 0 end), 0) as mediumCount, "
      + "coalesce(sum(case when d.riskLevel = com.orderpilot.domain.trust.TrustRiskLevel.HIGH then 1 else 0 end), 0) as highCount, "
      + "coalesce(sum(case when d.riskLevel = com.orderpilot.domain.trust.TrustRiskLevel.CRITICAL then 1 else 0 end), 0) as criticalCount, "
      + "coalesce(sum(case when d.humanReviewRequired = true then 1 else 0 end), 0) as approvalRequiredCount, "
      + "coalesce(sum(case when d.blocking = true then 1 else 0 end), 0) as blockingCount, "
      + "coalesce(sum(case when d.status = com.orderpilot.domain.trust.TrustRiskDecisionStatus.OVERRIDDEN then 1 else 0 end), 0) as overrideCount, "
      + "avg(d.riskScore) as avgRiskScore "
      + "from TrustRiskDecision d "
      + "where d.tenantId = :tenantId and d.createdAt >= :start and d.createdAt < :end")
  TrustRiskDistributionAggregate aggregateDistribution(
      @Param("tenantId") UUID tenantId, @Param("start") Instant start, @Param("end") Instant end);
}
