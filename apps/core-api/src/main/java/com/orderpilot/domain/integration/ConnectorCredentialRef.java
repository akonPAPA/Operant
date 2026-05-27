package com.orderpilot.domain.integration;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "connector_credential_ref")
public class ConnectorCredentialRef {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "integration_connection_id", nullable = false) private UUID integrationConnectionId;
  @Column(name = "secret_ref", nullable = false) private String secretRef;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private CredentialStatus status;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected ConnectorCredentialRef() {}

  public ConnectorCredentialRef(UUID tenantId, UUID integrationConnectionId, String secretRef, CredentialStatus status, Instant now) {
    this.tenantId = tenantId;
    this.integrationConnectionId = integrationConnectionId;
    this.secretRef = mask(secretRef);
    this.status = status;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getIntegrationConnectionId() { return integrationConnectionId; }
  public String getSecretRef() { return secretRef; }
  public CredentialStatus getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }

  public static String mask(String value) {
    if (value == null || value.isBlank()) return "placeholder:not-configured";
    return value.startsWith("placeholder:") ? value : "placeholder:" + Integer.toHexString(value.hashCode());
  }
}
