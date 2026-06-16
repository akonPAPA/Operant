package com.orderpilot.domain.journey;

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
 * OP-CAP-22 — a bounded fulfillment signal. No raw carrier/GPS/payment payload is stored; only a
 * {@code rawPayloadRef} object reference may point to an out-of-band record. Signals never trigger
 * external writes.
 */
@Entity
@Table(name = "fulfillment_signal")
public class FulfillmentSignal {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "journey_id") private UUID journeyId;
  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false) private FulfillmentSignalSource sourceType;
  @Enumerated(EnumType.STRING)
  @Column(name = "signal_type", nullable = false) private FulfillmentSignalType signalType;
  @Column(name = "signal_status") private String signalStatus;
  @Column private BigDecimal confidence;
  @Column(name = "source_ref") private String sourceRef;
  @Column(name = "raw_payload_ref") private String rawPayloadRef;
  @Column(name = "customer_visible", nullable = false) private boolean customerVisible;
  @Column(name = "received_at", nullable = false) private Instant receivedAt;
  @Column(name = "processed_at") private Instant processedAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected FulfillmentSignal() {}

  public FulfillmentSignal(UUID tenantId, UUID journeyId, FulfillmentSignalSource sourceType,
      FulfillmentSignalType signalType, String signalStatus, BigDecimal confidence, String sourceRef,
      String rawPayloadRef, boolean customerVisible, Instant receivedAt, Instant now) {
    this.tenantId = tenantId;
    this.journeyId = journeyId;
    this.sourceType = sourceType;
    this.signalType = signalType;
    this.signalStatus = signalStatus;
    this.confidence = confidence;
    this.sourceRef = sourceRef;
    this.rawPayloadRef = rawPayloadRef;
    this.customerVisible = customerVisible;
    this.receivedAt = receivedAt;
    this.processedAt = now;
    this.createdAt = now;
  }

  /** Best-known timestamp for the signal (processed time preferred, else received). */
  public Instant effectiveAt() {
    return processedAt != null ? processedAt : receivedAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getJourneyId() { return journeyId; }
  public FulfillmentSignalSource getSourceType() { return sourceType; }
  public FulfillmentSignalType getSignalType() { return signalType; }
  public String getSignalStatus() { return signalStatus; }
  public BigDecimal getConfidence() { return confidence; }
  public String getSourceRef() { return sourceRef; }
  public String getRawPayloadRef() { return rawPayloadRef; }
  public boolean isCustomerVisible() { return customerVisible; }
  public Instant getReceivedAt() { return receivedAt; }
  public Instant getProcessedAt() { return processedAt; }
  public Instant getCreatedAt() { return createdAt; }
}
