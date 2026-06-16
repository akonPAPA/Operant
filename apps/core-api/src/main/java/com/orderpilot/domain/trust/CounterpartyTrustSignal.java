package com.orderpilot.domain.trust;

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
 * OP-CAP-17B Counterparty Trust Profile Foundation.
 *
 * A counterparty-level trust signal derived from document trust, manual overrides, or (later) payment
 * and order behaviour. Severity reuses the 17A {@link TrustRiskLevel} scale (LOW/MEDIUM/HIGH/CRITICAL).
 * {@code explanation} is bounded and generic — never raw document text, account numbers, or bank
 * credentials. {@code weight} is a bounded int; business counters live on the profile as long.
 */
@Entity
@Table(name = "counterparty_trust_signal")
public class CounterpartyTrustSignal {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "customer_account_id", nullable = false) private UUID customerAccountId;

  @Enumerated(EnumType.STRING)
  @Column(name = "signal_code", nullable = false, length = 48) private CounterpartySignalCode signalCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 16) private TrustRiskLevel severity;

  /** Optional 0..1 confidence; null when not applicable. */
  @Column(name = "confidence", precision = 4, scale = 3) private BigDecimal confidence;

  @Column(name = "weight", nullable = false) private int weight;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 32) private CounterpartyTrustSourceType sourceType;

  @Column(name = "source_ref_id") private UUID sourceRefId;

  @Column(name = "explanation", length = 280) private String explanation;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected CounterpartyTrustSignal() {}

  public CounterpartyTrustSignal(UUID tenantId, UUID customerAccountId, CounterpartySignalCode signalCode,
      TrustRiskLevel severity, BigDecimal confidence, int weight, CounterpartyTrustSourceType sourceType,
      UUID sourceRefId, String explanation, Instant createdAt) {
    this.tenantId = tenantId;
    this.customerAccountId = customerAccountId;
    this.signalCode = signalCode;
    this.severity = severity;
    this.confidence = confidence;
    this.weight = weight;
    this.sourceType = sourceType;
    this.sourceRefId = sourceRefId;
    this.explanation = explanation;
    this.createdAt = createdAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public CounterpartySignalCode getSignalCode() { return signalCode; }
  public TrustRiskLevel getSeverity() { return severity; }
  public BigDecimal getConfidence() { return confidence; }
  public int getWeight() { return weight; }
  public CounterpartyTrustSourceType getSourceType() { return sourceType; }
  public UUID getSourceRefId() { return sourceRefId; }
  public String getExplanation() { return explanation; }
  public Instant getCreatedAt() { return createdAt; }
}
