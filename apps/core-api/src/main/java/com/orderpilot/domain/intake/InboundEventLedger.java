package com.orderpilot.domain.intake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inbound_event_ledger")
public class InboundEventLedger {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(nullable = false) private String source;
  @Column(name = "external_event_id") private String externalEventId;
  @Column(name = "event_type", nullable = false) private String eventType;
  @Column(name = "fingerprint_sha256") private String fingerprintSha256;
  @Column(nullable = false) private String status;
  @Column(name = "raw_payload_storage_key") private String rawPayloadStorageKey;
  @Column(name = "received_at", nullable = false) private Instant receivedAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected InboundEventLedger() {}

  public InboundEventLedger(UUID tenantId, String source, String externalEventId, String eventType, String fingerprintSha256, String status, String rawPayloadStorageKey, Instant now) {
    this.tenantId = tenantId;
    this.source = source;
    this.externalEventId = externalEventId;
    this.eventType = eventType;
    this.fingerprintSha256 = fingerprintSha256;
    this.status = status;
    this.rawPayloadStorageKey = rawPayloadStorageKey;
    this.receivedAt = now;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getSource() { return source; }
  public String getExternalEventId() { return externalEventId; }
  public String getEventType() { return eventType; }
  public String getFingerprintSha256() { return fingerprintSha256; }
  public String getStatus() { return status; }
  public String getRawPayloadStorageKey() { return rawPayloadStorageKey; }
}
