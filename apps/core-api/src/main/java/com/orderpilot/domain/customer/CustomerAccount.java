package com.orderpilot.domain.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_account")
public class CustomerAccount {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "external_ref") private String externalRef;
  @Column(name = "account_code", nullable = false) private String accountCode;
  @Column(name = "legal_name", nullable = false) private String legalName;
  @Column(name = "display_name", nullable = false) private String displayName;
  @Column(name = "segment_id") private UUID segmentId;
  @Column(nullable = false) private String status;
  @Column(name = "default_currency", nullable = false) private String defaultCurrency;
  @Column(name = "default_location_id") private UUID defaultLocationId;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Column(name = "deleted_at") private Instant deletedAt;

  protected CustomerAccount() {}

  public CustomerAccount(UUID tenantId, String externalRef, String accountCode, String legalName, String displayName, UUID segmentId, String status, String defaultCurrency, UUID defaultLocationId, Instant now) {
    this.tenantId = tenantId;
    this.externalRef = externalRef;
    this.accountCode = accountCode;
    this.legalName = legalName;
    this.displayName = displayName == null || displayName.isBlank() ? legalName : displayName;
    this.segmentId = segmentId;
    this.status = status == null || status.isBlank() ? "ACTIVE" : status;
    this.defaultCurrency = defaultCurrency == null || defaultCurrency.isBlank() ? "USD" : defaultCurrency;
    this.defaultLocationId = defaultLocationId;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void update(String legalName, String displayName, UUID segmentId, String status, String defaultCurrency, UUID defaultLocationId, Instant now) {
    if (legalName != null) this.legalName = legalName;
    if (displayName != null) this.displayName = displayName;
    if (segmentId != null) this.segmentId = segmentId;
    if (status != null) this.status = status;
    if (defaultCurrency != null) this.defaultCurrency = defaultCurrency;
    if (defaultLocationId != null) this.defaultLocationId = defaultLocationId;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getExternalRef() { return externalRef; }
  public String getAccountCode() { return accountCode; }
  public String getLegalName() { return legalName; }
  public String getDisplayName() { return displayName; }
  public UUID getSegmentId() { return segmentId; }
  public String getStatus() { return status; }
  public String getDefaultCurrency() { return defaultCurrency; }
  public UUID getDefaultLocationId() { return defaultLocationId; }
}