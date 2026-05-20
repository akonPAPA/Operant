package com.orderpilot.domain.channel;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "channel_connection")
public class ChannelConnection {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Enumerated(EnumType.STRING) @Column(name = "provider_type", nullable = false) private ChannelProviderType providerType;
  @Column(name = "display_name", nullable = false) private String displayName;
  @Column(nullable = false) private String status;
  @Column(nullable = false) private String mode;
  @Column(name = "external_account_id") private String externalAccountId;
  @Column(name = "webhook_url") private String webhookUrl;
  @Column(name = "secret_ref") private String secretRef;
  @Column(name = "last_health_check_at") private Instant lastHealthCheckAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected ChannelConnection() {}

  public ChannelConnection(UUID tenantId, ChannelProviderType providerType, String displayName, String externalAccountId, String webhookUrl, String secretRef, Instant now) {
    this.tenantId = tenantId;
    this.providerType = providerType;
    this.displayName = displayName;
    this.externalAccountId = externalAccountId;
    this.webhookUrl = webhookUrl;
    this.secretRef = secretRef;
    this.status = "DRAFT";
    this.mode = "READ_ONLY";
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void activate(Instant now) { this.status = "ACTIVE"; this.updatedAt = now; }
  public void pause(Instant now) { this.status = "PAUSED"; this.updatedAt = now; }
  public void disable(Instant now) { this.status = "DISABLED"; this.updatedAt = now; }
  public void recordHealthCheck(boolean healthy, Instant now) { this.status = healthy ? this.status : "ERROR"; this.lastHealthCheckAt = now; this.updatedAt = now; }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public ChannelProviderType getProviderType() { return providerType; }
  public String getDisplayName() { return displayName; }
  public String getStatus() { return status; }
  public String getMode() { return mode; }
  public String getExternalAccountId() { return externalAccountId; }
  public String getWebhookUrl() { return webhookUrl; }
  public String getSecretRef() { return secretRef; }
  public Instant getLastHealthCheckAt() { return lastHealthCheckAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
