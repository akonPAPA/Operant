package com.orderpilot.domain.trust.analytics;

import com.orderpilot.domain.payment.PaymentObligationStatus;
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
 * OP-CAP-17E Trust Analytics Read Models.
 *
 * Derived, rebuildable, tenant-scoped view of an OP-CAP-17C payment obligation that still carries
 * exposure (unpaid, partially paid, overdue, disputed, or written off). The obligation remains the
 * system of record; this row exists for fast finance/trust debt reads without scanning obligation
 * history. Amounts are {@link BigDecimal} NUMERIC(19,4) and currency is preserved per row (no FX, no
 * cross-currency aggregation). Unique per (tenant, obligation). No raw bank/PSP/card data is stored.
 */
@Entity
@Table(name = "outstanding_debt_view",
    uniqueConstraints = @UniqueConstraint(name = "ux_outstanding_debt_view_obligation",
        columnNames = {"tenant_id", "payment_obligation_id"}))
public class OutstandingDebtView {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "payment_obligation_id", nullable = false) private UUID paymentObligationId;

  @Column(name = "counterparty_id", nullable = false) private UUID counterpartyId;

  @Column(name = "order_id") private UUID orderId;

  @Column(name = "invoice_mirror_id") private UUID invoiceMirrorId;

  @Column(name = "external_reference", length = 120) private String externalReference;

  @Column(name = "amount_total", nullable = false, precision = 19, scale = 4) private BigDecimal amountTotal;

  @Column(name = "amount_paid", nullable = false, precision = 19, scale = 4) private BigDecimal amountPaid;

  @Column(name = "amount_remaining", nullable = false, precision = 19, scale = 4) private BigDecimal amountRemaining;

  @Column(name = "currency", nullable = false, length = 3) private String currency;

  @Column(name = "due_date") private LocalDate dueDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private PaymentObligationStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "risk_level", nullable = false, length = 16) private TrustRiskLevel riskLevel;

  @Column(name = "days_overdue", nullable = false) private int daysOverdue;

  @Column(name = "linked_risk_decision_id") private UUID linkedRiskDecisionId;

  @Column(name = "top_reason_code", length = 48) private String topReasonCode;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  @Column(name = "last_projected_at", nullable = false) private Instant lastProjectedAt;

  protected OutstandingDebtView() {}

  public OutstandingDebtView(UUID tenantId, UUID paymentObligationId, UUID counterpartyId, Instant createdAt) {
    this.tenantId = tenantId;
    this.paymentObligationId = paymentObligationId;
    this.counterpartyId = counterpartyId;
    this.createdAt = createdAt;
  }

  /** Idempotent in-place refresh of all projected fields. */
  public void apply(UUID counterpartyId, UUID orderId, UUID invoiceMirrorId, String externalReference,
      BigDecimal amountTotal, BigDecimal amountPaid, BigDecimal amountRemaining, String currency,
      LocalDate dueDate, PaymentObligationStatus status, TrustRiskLevel riskLevel, int daysOverdue,
      UUID linkedRiskDecisionId, String topReasonCode, Instant createdAt, Instant updatedAt,
      Instant projectedAt) {
    this.counterpartyId = counterpartyId;
    this.orderId = orderId;
    this.invoiceMirrorId = invoiceMirrorId;
    this.externalReference = externalReference;
    this.amountTotal = amountTotal;
    this.amountPaid = amountPaid;
    this.amountRemaining = amountRemaining;
    this.currency = currency;
    this.dueDate = dueDate;
    this.status = status;
    this.riskLevel = riskLevel;
    this.daysOverdue = daysOverdue;
    this.linkedRiskDecisionId = linkedRiskDecisionId;
    this.topReasonCode = topReasonCode;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.lastProjectedAt = projectedAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getPaymentObligationId() { return paymentObligationId; }
  public UUID getCounterpartyId() { return counterpartyId; }
  public UUID getOrderId() { return orderId; }
  public UUID getInvoiceMirrorId() { return invoiceMirrorId; }
  public String getExternalReference() { return externalReference; }
  public BigDecimal getAmountTotal() { return amountTotal; }
  public BigDecimal getAmountPaid() { return amountPaid; }
  public BigDecimal getAmountRemaining() { return amountRemaining; }
  public String getCurrency() { return currency; }
  public LocalDate getDueDate() { return dueDate; }
  public PaymentObligationStatus getStatus() { return status; }
  public TrustRiskLevel getRiskLevel() { return riskLevel; }
  public int getDaysOverdue() { return daysOverdue; }
  public UUID getLinkedRiskDecisionId() { return linkedRiskDecisionId; }
  public String getTopReasonCode() { return topReasonCode; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Instant getLastProjectedAt() { return lastProjectedAt; }
}
