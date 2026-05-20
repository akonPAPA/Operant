package com.orderpilot.domain.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_movement")
public class InventoryMovement {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "product_id", nullable = false) private UUID productId;
  @Column(name = "location_id", nullable = false) private UUID locationId;
  @Enumerated(EnumType.STRING)
  @Column(name = "movement_type", nullable = false) private InventoryMovementType movementType;
  @Column(nullable = false) private BigDecimal quantity;
  @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
  @Column(name = "source_type", nullable = false) private String sourceType;
  @Column(name = "source_reference") private String sourceReference;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected InventoryMovement() {}

  public InventoryMovement(UUID tenantId, UUID productId, UUID locationId, InventoryMovementType movementType, BigDecimal quantity, Instant occurredAt, String sourceType, String sourceReference, Instant now) {
    this.tenantId = tenantId;
    this.productId = productId;
    this.locationId = locationId;
    this.movementType = movementType;
    this.quantity = quantity;
    this.occurredAt = occurredAt;
    this.sourceType = sourceType;
    this.sourceReference = sourceReference;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getProductId() { return productId; }
  public UUID getLocationId() { return locationId; }
  public InventoryMovementType getMovementType() { return movementType; }
  public BigDecimal getQuantity() { return quantity; }
  public Instant getOccurredAt() { return occurredAt; }
  public String getSourceType() { return sourceType; }
  public String getSourceReference() { return sourceReference; }
  public Instant getCreatedAt() { return createdAt; }
}
