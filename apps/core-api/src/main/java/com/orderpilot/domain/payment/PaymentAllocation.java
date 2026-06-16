package com.orderpilot.domain.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * A safe, tenant-scoped allocation of an internal/mirrored payment amount to a {@link PaymentObligation}.
 * This is NOT a real bank transaction or PSP payload store — only the allocated amount, currency, and a
 * bounded internal reference are recorded. Reversal is deterministic and audited; reversed allocations
 * are never deleted (append-only evidence).
 */
@Entity
@Table(name = "payment_allocation")
public class PaymentAllocation {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "payment_obligation_id", nullable = false) private UUID paymentObligationId;

  @Column(name = "customer_account_id", nullable = false) private UUID customerAccountId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 24) private PaymentAllocationSourceType sourceType;

  @Column(name = "source_ref_id") private UUID sourceRefId;

  @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4) private BigDecimal allocatedAmount;

  @Column(name = "currency", nullable = false, length = 3) private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "allocation_status", nullable = false, length = 16) private PaymentAllocationStatus allocationStatus;

  @Column(name = "allocated_at", nullable = false) private Instant allocatedAt;

  @Column(name = "reversed_at") private Instant reversedAt;

  @Column(name = "reason_code", length = 80) private String reasonCode;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected PaymentAllocation() {}

  public PaymentAllocation(UUID tenantId, UUID paymentObligationId, UUID customerAccountId,
      PaymentAllocationSourceType sourceType, UUID sourceRefId, BigDecimal allocatedAmount, String currency,
      Instant allocatedAt, String reasonCode, Instant now) {
    this.tenantId = tenantId;
    this.paymentObligationId = paymentObligationId;
    this.customerAccountId = customerAccountId;
    this.sourceType = sourceType;
    this.sourceRefId = sourceRefId;
    this.allocatedAmount = allocatedAmount;
    this.currency = currency;
    this.allocationStatus = PaymentAllocationStatus.APPLIED;
    this.allocatedAt = allocatedAt;
    this.reasonCode = reasonCode;
    this.createdAt = now;
  }

  public boolean isApplied() {
    return allocationStatus == PaymentAllocationStatus.APPLIED;
  }

  public void reverse(String reasonCode, Instant now) {
    this.allocationStatus = PaymentAllocationStatus.REVERSED;
    this.reversedAt = now;
    if (reasonCode != null) {
      this.reasonCode = reasonCode;
    }
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getPaymentObligationId() { return paymentObligationId; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public PaymentAllocationSourceType getSourceType() { return sourceType; }
  public UUID getSourceRefId() { return sourceRefId; }
  public BigDecimal getAllocatedAmount() { return allocatedAmount; }
  public String getCurrency() { return currency; }
  public PaymentAllocationStatus getAllocationStatus() { return allocationStatus; }
  public Instant getAllocatedAt() { return allocatedAt; }
  public Instant getReversedAt() { return reversedAt; }
  public String getReasonCode() { return reasonCode; }
  public Instant getCreatedAt() { return createdAt; }
}
