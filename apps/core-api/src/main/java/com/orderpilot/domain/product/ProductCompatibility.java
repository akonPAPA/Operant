package com.orderpilot.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_compatibility")
public class ProductCompatibility {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "product_id", nullable = false) private UUID productId;
  @Column(name = "compatible_type", nullable = false) private String compatibleType;
  private String make;
  private String model;
  @Column(name = "year_from") private Integer yearFrom;
  @Column(name = "year_to") private Integer yearTo;
  private String configuration;
  private String notes;
  @Column(name = "risk_level", nullable = false) private String riskLevel;
  @Column(nullable = false) private boolean active;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected ProductCompatibility() {}
  public ProductCompatibility(UUID tenantId, UUID productId, String compatibleType, String make, String model, Integer yearFrom, Integer yearTo, String configuration, String notes, String riskLevel, Instant now) {
    this.tenantId = tenantId;
    this.productId = productId;
    this.compatibleType = compatibleType;
    this.make = make;
    this.model = model;
    this.yearFrom = yearFrom;
    this.yearTo = yearTo;
    this.configuration = configuration;
    this.notes = notes;
    this.riskLevel = riskLevel == null || riskLevel.isBlank() ? "MEDIUM" : riskLevel;
    this.active = true;
    this.createdAt = now;
    this.updatedAt = now;
  }
  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getProductId() { return productId; }
  public String getCompatibleType() { return compatibleType; }
  public String getMake() { return make; }
  public String getModel() { return model; }
  public Integer getYearFrom() { return yearFrom; }
  public Integer getYearTo() { return yearTo; }
  public String getConfiguration() { return configuration; }
  public String getNotes() { return notes; }
  public String getRiskLevel() { return riskLevel; }
  public boolean isActive() { return active; }
}
