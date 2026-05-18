package com.orderpilot.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_substitute")
public class ProductSubstitute {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "source_product_id", nullable = false) private UUID sourceProductId;
  @Column(name = "substitute_product_id", nullable = false) private UUID substituteProductId;
  @Column(name = "substitute_type", nullable = false) private String substituteType;
  @Column(name = "risk_level", nullable = false) private String riskLevel;
  @Column(name = "requires_approval", nullable = false) private boolean requiresApproval;
  private String notes;
  @Column(nullable = false) private boolean active;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected ProductSubstitute() {}
  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getSourceProductId() { return sourceProductId; }
  public UUID getSubstituteProductId() { return substituteProductId; }
  public String getSubstituteType() { return substituteType; }
  public String getRiskLevel() { return riskLevel; }
  public boolean isRequiresApproval() { return requiresApproval; }
  public boolean isActive() { return active; }
}
