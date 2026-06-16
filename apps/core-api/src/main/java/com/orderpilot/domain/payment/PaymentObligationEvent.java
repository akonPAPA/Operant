package com.orderpilot.domain.payment;

import com.orderpilot.domain.trust.TrustRiskLevel;
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
 * Append-only history of a payment obligation's status/amount transitions. Never mutated after
 * creation. {@code reasonSummary} is bounded and generic — never raw bank/PSP payloads, account
 * numbers, document text, or prompt text. Used as audit/read-model evidence.
 */
@Entity
@Table(name = "payment_obligation_event")
public class PaymentObligationEvent {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "payment_obligation_id", nullable = false) private UUID paymentObligationId;

  @Column(name = "customer_account_id", nullable = false) private UUID customerAccountId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 32) private PaymentObligationEventType eventType;

  @Enumerated(EnumType.STRING)
  @Column(name = "previous_status", length = 16) private PaymentObligationStatus previousStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "new_status", nullable = false, length = 16) private PaymentObligationStatus newStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "new_risk_level", nullable = false, length = 16) private TrustRiskLevel newRiskLevel;

  @Column(name = "previous_amount_paid", precision = 19, scale = 4) private BigDecimal previousAmountPaid;

  @Column(name = "new_amount_paid", nullable = false, precision = 19, scale = 4) private BigDecimal newAmountPaid;

  @Column(name = "previous_amount_remaining", precision = 19, scale = 4) private BigDecimal previousAmountRemaining;

  @Column(name = "new_amount_remaining", nullable = false, precision = 19, scale = 4) private BigDecimal newAmountRemaining;

  @Column(name = "reason_summary", length = 280) private String reasonSummary;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 24) private PaymentObligationSourceType sourceType;

  @Column(name = "source_ref_id") private UUID sourceRefId;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected PaymentObligationEvent() {}

  public PaymentObligationEvent(UUID tenantId, UUID paymentObligationId, UUID customerAccountId,
      PaymentObligationEventType eventType, PaymentObligationStatus previousStatus,
      PaymentObligationStatus newStatus, TrustRiskLevel newRiskLevel, BigDecimal previousAmountPaid,
      BigDecimal newAmountPaid, BigDecimal previousAmountRemaining, BigDecimal newAmountRemaining,
      String reasonSummary, PaymentObligationSourceType sourceType, UUID sourceRefId, Instant createdAt) {
    this.tenantId = tenantId;
    this.paymentObligationId = paymentObligationId;
    this.customerAccountId = customerAccountId;
    this.eventType = eventType;
    this.previousStatus = previousStatus;
    this.newStatus = newStatus;
    this.newRiskLevel = newRiskLevel;
    this.previousAmountPaid = previousAmountPaid;
    this.newAmountPaid = newAmountPaid;
    this.previousAmountRemaining = previousAmountRemaining;
    this.newAmountRemaining = newAmountRemaining;
    this.reasonSummary = reasonSummary;
    this.sourceType = sourceType;
    this.sourceRefId = sourceRefId;
    this.createdAt = createdAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getPaymentObligationId() { return paymentObligationId; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public PaymentObligationEventType getEventType() { return eventType; }
  public PaymentObligationStatus getPreviousStatus() { return previousStatus; }
  public PaymentObligationStatus getNewStatus() { return newStatus; }
  public TrustRiskLevel getNewRiskLevel() { return newRiskLevel; }
  public BigDecimal getPreviousAmountPaid() { return previousAmountPaid; }
  public BigDecimal getNewAmountPaid() { return newAmountPaid; }
  public BigDecimal getPreviousAmountRemaining() { return previousAmountRemaining; }
  public BigDecimal getNewAmountRemaining() { return newAmountRemaining; }
  public String getReasonSummary() { return reasonSummary; }
  public PaymentObligationSourceType getSourceType() { return sourceType; }
  public UUID getSourceRefId() { return sourceRefId; }
  public Instant getCreatedAt() { return createdAt; }
}
