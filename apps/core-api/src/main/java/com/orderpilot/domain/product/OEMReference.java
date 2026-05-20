package com.orderpilot.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "oem_reference")
public class OEMReference {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "product_id", nullable = false) private UUID productId;
  @Column(name = "oem_code", nullable = false) private String oemCode;
  private String manufacturer;
  @Column(name = "normalized_oem_code", nullable = false) private String normalizedOemCode;
  @Column(nullable = false) private boolean active;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected OEMReference() {}
  public OEMReference(UUID tenantId, UUID productId, String oemCode, String normalizedOemCode, String manufacturer, Instant now) {
    this.tenantId = tenantId;
    this.productId = productId;
    this.oemCode = oemCode;
    this.normalizedOemCode = normalizedOemCode;
    this.manufacturer = manufacturer;
    this.active = true;
    this.createdAt = now;
    this.updatedAt = now;
  }
  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getProductId() { return productId; }
  public String getOemCode() { return oemCode; }
  public String getNormalizedOemCode() { return normalizedOemCode; }
  public boolean isActive() { return active; }
}
