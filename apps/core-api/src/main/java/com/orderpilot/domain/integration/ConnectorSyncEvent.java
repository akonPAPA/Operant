package com.orderpilot.domain.integration;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "connector_sync_event")
public class ConnectorSyncEvent {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "integration_connection_id", nullable = false) private UUID integrationConnectionId;
  @Enumerated(EnumType.STRING) @Column(name = "provider_type", nullable = false) private IntegrationProviderType providerType;
  @Column(name = "sync_type", nullable = false) private String syncType;
  @Column(nullable = false) private String direction;
  @Column(nullable = false) private String status;
  @Column(name = "records_read", nullable = false) private int recordsRead;
  @Column(name = "records_written", nullable = false) private int recordsWritten;
  @Column(name = "records_failed", nullable = false) private int recordsFailed;
  @Column(name = "started_at", nullable = false) private Instant startedAt;
  @Column(name = "finished_at") private Instant finishedAt;
  @Column(name = "error_code") private String errorCode;
  @Column(name = "error_message") private String errorMessage;

  protected ConnectorSyncEvent() {}

  public ConnectorSyncEvent(UUID tenantId, UUID integrationConnectionId, IntegrationProviderType providerType, String syncType, String direction, Instant now) {
    this.tenantId = tenantId;
    this.integrationConnectionId = integrationConnectionId;
    this.providerType = providerType;
    this.syncType = syncType;
    this.direction = direction;
    this.status = "STARTED";
    this.startedAt = now;
  }

  public void complete(int recordsRead, int recordsWritten, int recordsFailed, Instant now) {
    this.recordsRead = recordsRead; this.recordsWritten = recordsWritten; this.recordsFailed = recordsFailed; this.status = recordsFailed > 0 ? "PARTIAL_SUCCESS" : "SUCCESS"; this.finishedAt = now;
  }
  public void fail(String errorCode, String errorMessage, Instant now) { this.status = "FAILED"; this.errorCode = errorCode; this.errorMessage = errorMessage; this.finishedAt = now; }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getIntegrationConnectionId() { return integrationConnectionId; }
  public IntegrationProviderType getProviderType() { return providerType; }
  public String getSyncType() { return syncType; }
  public String getDirection() { return direction; }
  public String getStatus() { return status; }
  public int getRecordsRead() { return recordsRead; }
  public int getRecordsWritten() { return recordsWritten; }
  public int getRecordsFailed() { return recordsFailed; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getFinishedAt() { return finishedAt; }
  public String getErrorCode() { return errorCode; }
  public String getErrorMessage() { return errorMessage; }
}
