package com.orderpilot.domain.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * Tenant-scoped, bounded allocation queries. The caller supplies a clamped {@link Pageable}.
 */
public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, UUID> {
  Optional<PaymentAllocation> findByIdAndTenantId(UUID id, UUID tenantId);

  List<PaymentAllocation> findByTenantIdAndPaymentObligationIdOrderByAllocatedAtDesc(
      UUID tenantId, UUID paymentObligationId, Pageable pageable);

  List<PaymentAllocation> findByTenantIdAndCustomerAccountIdOrderByAllocatedAtDesc(
      UUID tenantId, UUID customerAccountId, Pageable pageable);
}
