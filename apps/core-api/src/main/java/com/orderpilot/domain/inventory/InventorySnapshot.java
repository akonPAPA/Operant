package com.orderpilot.domain.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_snapshot")
public class InventorySnapshot {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "product_id", nullable = false) private UUID productId;
  @Column(name = "location_id", nullable = false) private UUID locationId;
  @Column(name = "quantity_on_hand", nullable = false) private BigDecimal quantityOnHand;
  @Column(name = "quantity_available", nullable = false) private BigDecimal quantityAvailable;
  @Column(name = "quantity_reserved", nullable = false) private BigDecimal quantityReserved;
  @Column(name = "captured_at", nullable = false) private Instant capturedAt;
  @Column(nullable = false) private String source;
  @Column(name = "import_job_id") private UUID importJobId;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  protected InventorySnapshot() {}

  public InventorySnapshot(UUID tenantId, UUID productId, UUID locationId, BigDecimal quantityOnHand, BigDecimal quantityAvailable, BigDecimal quantityReserved, Instant capturedAt, String source, UUID importJobId, Instant now) {
    this.tenantId = tenantId;
    this.productId = productId;
    this.locationId = locationId;
    this.quantityOnHand = quantityOnHand;
    this.quantityAvailable = quantityAvailable;
    this.quantityReserved = quantityReserved == null ? BigDecimal.ZERO : quantityReserved;
    this.capturedAt = capturedAt;
    this.source = source;
    this.importJobId = importJobId;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getProductId() { return productId; }
  public UUID getLocationId() { return locationId; }
  public BigDecimal getQuantityOnHand() { return quantityOnHand; }
  public BigDecimal getQuantityAvailable() { return quantityAvailable; }
  public BigDecimal getQuantityReserved() { return quantityReserved; }
  public Instant getCapturedAt() { return capturedAt; }
  public String getSource() { return source; }
}
