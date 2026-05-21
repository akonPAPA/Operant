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
  @Column(name = "secret_reference_id") private String secretReferenceId;
  @Column(name = "secret_last_updated_at") private Instant secretLastUpdatedAt;
  @Column(name = "webhook_verification_mode", nullable = false) private String webhookVerificationMode;
  @Column(name = "last_health_check_at") private Instant lastHealthCheckAt;
  @Column(name = "last_health_check_status") private String lastHealthCheckStatus;
  @Column(name = "last_diagnostic_summary") private String lastDiagnosticSummary;
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
    this.secretReferenceId = secretRef;
    this.secretLastUpdatedAt = secretRef == null || secretRef.isBlank() ? null : now;
    this.webhookVerificationMode = "DISABLED_FOR_LOCAL_DEV";
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
  public void configureWebhookVerificationMode(String webhookVerificationMode, Instant now) { this.webhookVerificationMode = webhookVerificationMode; this.updatedAt = now; }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public ChannelProviderType getProviderType() { return providerType; }
  public String getDisplayName() { return displayName; }
  public String getStatus() { return status; }
  public String getMode() { return mode; }
  public String getExternalAccountId() { return externalAccountId; }
  public String getWebhookUrl() { return webhookUrl; }
  public String getSecretRef() { return secretRef; }
  public String getSecretReferenceId() { return secretReferenceId; }
  public Instant getSecretLastUpdatedAt() { return secretLastUpdatedAt; }
  public String getWebhookVerificationMode() { return webhookVerificationMode; }
  public Instant getLastHealthCheckAt() { return lastHealthCheckAt; }
  public String getLastHealthCheckStatus() { return lastHealthCheckStatus; }
  public String getLastDiagnosticSummary() { return lastDiagnosticSummary; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
