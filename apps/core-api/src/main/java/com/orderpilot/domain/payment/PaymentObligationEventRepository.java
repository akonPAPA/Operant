package com.orderpilot.domain.payment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * Append-only, tenant-scoped, bounded event queries. The caller supplies a clamped {@link Pageable}.
 */
public interface PaymentObligationEventRepository extends JpaRepository<PaymentObligationEvent, UUID> {
  List<PaymentObligationEvent> findByTenantIdAndPaymentObligationIdOrderByCreatedAtDesc(
      UUID tenantId, UUID paymentObligationId, Pageable pageable);

  List<PaymentObligationEvent> findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(
      UUID tenantId, UUID customerAccountId, Pageable pageable);
}
