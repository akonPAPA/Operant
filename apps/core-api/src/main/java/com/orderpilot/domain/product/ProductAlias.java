package com.orderpilot.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_alias")
public class ProductAlias {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "product_id", nullable = false) private UUID productId;
  @Column(name = "alias_type", nullable = false) private String aliasType;
  @Column(name = "raw_alias", nullable = false) private String rawAlias;
  @Column(name = "normalized_alias", nullable = false) private String normalizedAlias;
  @Column(name = "customer_account_id") private UUID customerAccountId;
  @Column(name = "confidence_default") private BigDecimal confidenceDefault;
  @Column(nullable = false) private boolean active;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected ProductAlias() {}

  public ProductAlias(UUID tenantId, UUID productId, String aliasType, String rawAlias, String normalizedAlias, UUID customerAccountId, BigDecimal confidenceDefault, Instant now) {
    this.tenantId = tenantId;
    this.productId = productId;
    this.aliasType = aliasType == null || aliasType.isBlank() ? "OTHER" : aliasType;
    this.rawAlias = rawAlias;
    this.normalizedAlias = normalizedAlias;
    this.customerAccountId = customerAccountId;
    this.confidenceDefault = confidenceDefault;
    this.active = true;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getProductId() { return productId; }
  public String getAliasType() { return aliasType; }
  public String getRawAlias() { return rawAlias; }
  public String getNormalizedAlias() { return normalizedAlias; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public BigDecimal getConfidenceDefault() { return confidenceDefault; }
  public boolean isActive() { return active; }
}