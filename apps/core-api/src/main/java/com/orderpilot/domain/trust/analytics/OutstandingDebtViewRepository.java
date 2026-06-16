package com.orderpilot.domain.trust.analytics;

import com.orderpilot.domain.payment.PaymentObligationStatus;
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
public interface OutstandingDebtViewRepository extends JpaRepository<OutstandingDebtView, UUID> {
  Optional<OutstandingDebtView> findByTenantIdAndPaymentObligationId(UUID tenantId, UUID paymentObligationId);

  List<OutstandingDebtView> findByTenantIdOrderByAmountRemainingDesc(UUID tenantId, Pageable pageable);

  List<OutstandingDebtView> findByTenantIdAndStatusOrderByAmountRemainingDesc(
      UUID tenantId, PaymentObligationStatus status, Pageable pageable);

  List<OutstandingDebtView> findByTenantIdAndRiskLevelOrderByAmountRemainingDesc(
      UUID tenantId, TrustRiskLevel riskLevel, Pageable pageable);

  List<OutstandingDebtView> findByTenantIdAndStatusAndRiskLevelOrderByAmountRemainingDesc(
      UUID tenantId, PaymentObligationStatus status, TrustRiskLevel riskLevel, Pageable pageable);

  List<OutstandingDebtView> findByTenantIdAndCounterpartyIdOrderByAmountRemainingDesc(
      UUID tenantId, UUID counterpartyId, Pageable pageable);
}
