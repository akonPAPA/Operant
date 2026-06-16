package com.orderpilot.domain.payment;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * Tenant-scoped, bounded queries only. The caller always supplies a clamped {@link Pageable}; there is
 * no unbounded per-request history scan. The per-status aggregate is bounded to at most one row per
 * status. Every finder is tenant-isolated.
 */
public interface PaymentObligationRepository extends JpaRepository<PaymentObligation, UUID> {
  Optional<PaymentObligation> findByIdAndTenantId(UUID id, UUID tenantId);

  Optional<PaymentObligation> findByTenantIdAndSourceTypeAndSourceRefId(
      UUID tenantId, PaymentObligationSourceType sourceType, UUID sourceRefId);

  boolean existsByTenantIdAndSourceTypeAndSourceRefId(
      UUID tenantId, PaymentObligationSourceType sourceType, UUID sourceRefId);

  List<PaymentObligation> findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(
      UUID tenantId, UUID customerAccountId, Pageable pageable);

  List<PaymentObligation> findByTenantIdAndCustomerAccountIdAndStatusOrderByCreatedAtDesc(
      UUID tenantId, UUID customerAccountId, PaymentObligationStatus status, Pageable pageable);

  /** Bounded list of active obligations past their due date (for deterministic overdue detection). */
  @Query("select o from PaymentObligation o "
      + "where o.tenantId = :tenantId and o.customerAccountId = :customerAccountId "
      + "and o.status in :statuses and o.dueDate is not null and o.dueDate < :today "
      + "order by o.dueDate asc")
  List<PaymentObligation> findOverdueCandidates(
      @Param("tenantId") UUID tenantId,
      @Param("customerAccountId") UUID customerAccountId,
      @Param("statuses") Collection<PaymentObligationStatus> statuses,
      @Param("today") LocalDate today,
      Pageable pageable);

  /** Bounded per-status aggregation (at most one row per status). */
  @Query("select o.status as status, count(o) as obligationCount, "
      + "coalesce(sum(o.amountTotal), 0) as totalAmount, "
      + "coalesce(sum(o.amountPaid), 0) as paidAmount, "
      + "coalesce(sum(o.amountRemaining), 0) as remainingAmount "
      + "from PaymentObligation o "
      + "where o.tenantId = :tenantId and o.customerAccountId = :customerAccountId "
      + "group by o.status")
  List<PaymentObligationStatusAggregate> aggregateByStatus(
      @Param("tenantId") UUID tenantId, @Param("customerAccountId") UUID customerAccountId);

  /** Bounded distinct currencies for a counterparty (used to decide single vs multi-currency summary). */
  @Query("select distinct o.currency from PaymentObligation o "
      + "where o.tenantId = :tenantId and o.customerAccountId = :customerAccountId")
  List<String> findDistinctCurrencies(
      @Param("tenantId") UUID tenantId, @Param("customerAccountId") UUID customerAccountId);
}
