package com.orderpilot.domain.payment;

import com.orderpilot.domain.trust.TrustRiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * Tenant-scoped expected receivable/payment obligation linked to a counterparty (customer account)
 * and optionally to a mirrored quote/order/invoice reference. Amounts are {@link BigDecimal}
 * NUMERIC(19,4) — never floating point. Risk reuses the deterministic {@link TrustRiskLevel} scale so
 * payment risk aligns 1:1 with counterparty trust signal severity.
 *
 * <p>No raw bank credentials, IBAN, routing/account numbers, PAN/CVV, NFC payloads, raw bank
 * statement payloads, raw document text, or prompt text are ever stored here. {@code externalReference}
 * and {@code obligationNumber} are bounded, safe business identifiers only. Status/risk transitions are
 * decided deterministically by the backend service — never by AI, bot, frontend, connector, or webhook.
 */
@Entity
@Table(name = "payment_obligation",
    uniqueConstraints = @UniqueConstraint(name = "ux_payment_obligation_tenant_source_ref",
        columnNames = {"tenant_id", "source_type", "source_ref_id"}))
public class PaymentObligation {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "customer_account_id", nullable = false) private UUID customerAccountId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 24) private PaymentObligationSourceType sourceType;

  @Column(name = "source_ref_id") private UUID sourceRefId;

  @Column(name = "external_reference", length = 120) private String externalReference;

  @Column(name = "obligation_number", length = 80) private String obligationNumber;

  @Column(name = "amount_total", nullable = false, precision = 19, scale = 4) private BigDecimal amountTotal;

  @Column(name = "amount_paid", nullable = false, precision = 19, scale = 4) private BigDecimal amountPaid;

  @Column(name = "amount_remaining", nullable = false, precision = 19, scale = 4) private BigDecimal amountRemaining;

  @Column(name = "currency", nullable = false, length = 3) private String currency;

  @Column(name = "due_date") private LocalDate dueDate;

  @Column(name = "issued_at") private Instant issuedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private PaymentObligationStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "risk_level", nullable = false, length = 16) private TrustRiskLevel riskLevel;

  @Column(name = "last_payment_at") private Instant lastPaymentAt;

  @Column(name = "disputed_at") private Instant disputedAt;

  @Column(name = "closed_at") private Instant closedAt;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected PaymentObligation() {}

  public PaymentObligation(UUID tenantId, UUID customerAccountId, PaymentObligationSourceType sourceType,
      UUID sourceRefId, String externalReference, String obligationNumber, BigDecimal amountTotal,
      String currency, LocalDate dueDate, Instant issuedAt, PaymentObligationStatus status,
      TrustRiskLevel riskLevel, Instant now) {
    this.tenantId = tenantId;
    this.customerAccountId = customerAccountId;
    this.sourceType = sourceType;
    this.sourceRefId = sourceRefId;
    this.externalReference = externalReference;
    this.obligationNumber = obligationNumber;
    this.amountTotal = amountTotal;
    this.amountPaid = BigDecimal.ZERO.setScale(amountTotal.scale());
    this.amountRemaining = amountTotal;
    this.currency = currency;
    this.dueDate = dueDate;
    this.issuedAt = issuedAt;
    this.status = status;
    this.riskLevel = riskLevel;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** Applies a payment allocation. Caller validates non-overpayment and currency before calling. */
  public void addPayment(BigDecimal amount, Instant paymentAt, Instant now) {
    this.amountPaid = this.amountPaid.add(amount);
    this.amountRemaining = this.amountTotal.subtract(this.amountPaid).max(BigDecimal.ZERO.setScale(this.amountTotal.scale()));
    this.lastPaymentAt = paymentAt;
    this.updatedAt = now;
  }

  /** Reverses a previously applied allocation amount. Paid is floored at zero. */
  public void removePayment(BigDecimal amount, Instant now) {
    this.amountPaid = this.amountPaid.subtract(amount).max(BigDecimal.ZERO.setScale(this.amountTotal.scale()));
    this.amountRemaining = this.amountTotal.subtract(this.amountPaid).max(BigDecimal.ZERO.setScale(this.amountTotal.scale()));
    this.updatedAt = now;
  }

  public void applyStatusAndRisk(PaymentObligationStatus status, TrustRiskLevel riskLevel, Instant now) {
    this.status = status;
    this.riskLevel = riskLevel;
    this.updatedAt = now;
  }

  public void markDisputed(Instant now) {
    this.status = PaymentObligationStatus.DISPUTED;
    this.riskLevel = TrustRiskLevel.HIGH;
    if (this.disputedAt == null) {
      this.disputedAt = now;
    }
    this.updatedAt = now;
  }

  public void markClosed(PaymentObligationStatus terminalStatus, TrustRiskLevel riskLevel, Instant now) {
    this.status = terminalStatus;
    this.riskLevel = riskLevel;
    this.closedAt = now;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public PaymentObligationSourceType getSourceType() { return sourceType; }
  public UUID getSourceRefId() { return sourceRefId; }
  public String getExternalReference() { return externalReference; }
  public String getObligationNumber() { return obligationNumber; }
  public BigDecimal getAmountTotal() { return amountTotal; }
  public BigDecimal getAmountPaid() { return amountPaid; }
  public BigDecimal getAmountRemaining() { return amountRemaining; }
  public String getCurrency() { return currency; }
  public LocalDate getDueDate() { return dueDate; }
  public Instant getIssuedAt() { return issuedAt; }
  public PaymentObligationStatus getStatus() { return status; }
  public TrustRiskLevel getRiskLevel() { return riskLevel; }
  public Instant getLastPaymentAt() { return lastPaymentAt; }
  public Instant getDisputedAt() { return disputedAt; }
  public Instant getClosedAt() { return closedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
