package com.orderpilot.domain.integration;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "integration_connection")
public class IntegrationConnection {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Enumerated(EnumType.STRING) @Column(name = "provider_type", nullable = false) private IntegrationProviderType providerType;
  @Column(name = "display_name", nullable = false) private String displayName;
  @Column(nullable = false) private String status;
  @Column(nullable = false) private String mode;
  @Column(name = "connection_kind", nullable = false) private String connectionKind;
  @Column(name = "secret_ref") private String secretRef;
  @Column(name = "endpoint_ref") private String endpointRef;
  @Column(name = "last_sync_at") private Instant lastSyncAt;
  @Column(name = "last_health_check_at") private Instant lastHealthCheckAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected IntegrationConnection() {}

  public IntegrationConnection(UUID tenantId, IntegrationProviderType providerType, String displayName, String connectionKind, String secretRef, String endpointRef, Instant now) {
    this.tenantId = tenantId;
    this.providerType = providerType;
    this.displayName = displayName;
    this.connectionKind = connectionKind == null || connectionKind.isBlank() ? "MANUAL_UPLOAD" : connectionKind;
    this.secretRef = secretRef;
    this.endpointRef = endpointRef;
    this.status = "DRAFT";
    this.mode = "READ_ONLY";
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void activate(Instant now) { this.status = "ACTIVE"; this.updatedAt = now; }
  public void pause(Instant now) { this.status = "PAUSED"; this.updatedAt = now; }
  public void disable(Instant now) { this.status = "DISABLED"; this.updatedAt = now; }
  public void recordHealthCheck(boolean healthy, Instant now) { this.status = healthy ? this.status : "ERROR"; this.lastHealthCheckAt = now; this.updatedAt = now; }
  public void markSynced(Instant now) { this.lastSyncAt = now; this.updatedAt = now; }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public IntegrationProviderType getProviderType() { return providerType; }
  public String getDisplayName() { return displayName; }
  public String getStatus() { return status; }
  public String getMode() { return mode; }
  public String getConnectionKind() { return connectionKind; }
  public String getSecretRef() { return secretRef; }
  public String getEndpointRef() { return endpointRef; }
  public Instant getLastSyncAt() { return lastSyncAt; }
  public Instant getLastHealthCheckAt() { return lastHealthCheckAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
