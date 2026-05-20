package com.orderpilot.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "product")
public class Product {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(nullable = false) private String sku;
  @Column(name = "normalized_sku", nullable = false) private String normalizedSku;
  @Column(nullable = false) private String name;
  private String description;
  private String category;
  private String brand;
  private String manufacturer;
  @Column(name = "base_uom", nullable = false) private String baseUom;
  @Column(nullable = false) private String status;
  private BigDecimal cost;
  @Size(min = 3, max = 3)
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 3, columnDefinition = "char(3)") private String currency;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Column(name = "deleted_at") private Instant deletedAt;

  protected Product() {}

  public Product(UUID tenantId, String sku, String name, String description, String category, String brand, String manufacturer, String baseUom, String status, BigDecimal cost, String currency, Instant now) {
    this.tenantId = tenantId;
    this.sku = sku;
    this.normalizedSku = com.orderpilot.application.services.ProductCodeNormalizer.normalize(sku);
    this.name = name;
    this.description = description;
    this.category = category;
    this.brand = brand;
    this.manufacturer = manufacturer;
    this.baseUom = baseUom;
    this.status = status == null || status.isBlank() ? "ACTIVE" : status;
    this.cost = cost;
    this.currency = currency;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void update(String name, String description, String category, String brand, String manufacturer, String baseUom, String status, BigDecimal cost, String currency, Instant now) {
    if (name != null) this.name = name;
    if (description != null) this.description = description;
    if (category != null) this.category = category;
    if (brand != null) this.brand = brand;
    if (manufacturer != null) this.manufacturer = manufacturer;
    if (baseUom != null) this.baseUom = baseUom;
    if (status != null) this.status = status;
    if (cost != null) this.cost = cost;
    if (currency != null) this.currency = currency;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getSku() { return sku; }
  public String getNormalizedSku() { return normalizedSku; }
  public String getName() { return name; }
  public String getDescription() { return description; }
  public String getCategory() { return category; }
  public String getBrand() { return brand; }
  public String getManufacturer() { return manufacturer; }
  public String getBaseUom() { return baseUom; }
  public String getStatus() { return status; }
  public BigDecimal getCost() { return cost; }
  public String getCurrency() { return currency; }
}
