package com.orderpilot.domain.integration;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
  @Column(name = "secret_reference_id") private String secretReferenceId;
  @Column(name = "secret_last_updated_at") private Instant secretLastUpdatedAt;
  @Column(name = "endpoint_ref") private String endpointRef;
  @Column(name = "last_sync_at") private Instant lastSyncAt;
  @Column(name = "last_health_check_at") private Instant lastHealthCheckAt;
  @Column(name = "last_health_check_status") private String lastHealthCheckStatus;
  @Column(name = "last_diagnostic_summary") private String lastDiagnosticSummary;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected IntegrationConnection() {}

  public IntegrationConnection(UUID tenantId, IntegrationProviderType providerType, String displayName, String connectionKind, String secretRef, String endpointRef, Instant now) {
    this.tenantId = tenantId;
    this.providerType = providerType;
    this.displayName = displayName;
    this.connectionKind = connectionKind == null || connectionKind.isBlank() ? "MANUAL_UPLOAD" : connectionKind;
    this.secretRef = secretRef;
    this.secretReferenceId = secretRef;
    this.secretLastUpdatedAt = secretRef == null || secretRef.isBlank() ? null : now;
    this.endpointRef = endpointRef;
    this.status = "DRAFT";
    this.mode = "READ_ONLY";
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void activate(Instant now) { this.status = "ACTIVE"; this.updatedAt = now; }
  public void pause(Instant now) { this.status = "PAUSED"; this.updatedAt = now; }
  public void disable(Instant now) { this.status = "DISABLED"; this.updatedAt = now; }
  public void recordHealthCheck(boolean healthy, String statusCode, String diagnosticSummary, Instant now) { this.status = healthy ? this.status : "ERROR"; this.lastHealthCheckAt = now; this.lastHealthCheckStatus = statusCode; this.lastDiagnosticSummary = diagnosticSummary; this.updatedAt = now; }
  public void configureSecret(String secretReferenceId, Instant now) { this.secretRef = secretReferenceId; this.secretReferenceId = secretReferenceId; this.secretLastUpdatedAt = now; this.updatedAt = now; }
  public void markSynced(Instant now) { this.lastSyncAt = now; this.updatedAt = now; }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public IntegrationProviderType getProviderType() { return providerType; }
  public String getDisplayName() { return displayName; }
  public String getStatus() { return status; }
  public String getMode() { return mode; }
  public String getConnectionKind() { return connectionKind; }
  @JsonIgnore
  public String getSecretRef() { return secretRef; }
  @JsonIgnore
  public String getSecretReferenceId() { return secretReferenceId; }
  public Instant getSecretLastUpdatedAt() { return secretLastUpdatedAt; }
  public String getEndpointRef() { return endpointRef; }
  public Instant getLastSyncAt() { return lastSyncAt; }
  public Instant getLastHealthCheckAt() { return lastHealthCheckAt; }
  public String getLastHealthCheckStatus() { return lastHealthCheckStatus; }
  public String getLastDiagnosticSummary() { return lastDiagnosticSummary; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
