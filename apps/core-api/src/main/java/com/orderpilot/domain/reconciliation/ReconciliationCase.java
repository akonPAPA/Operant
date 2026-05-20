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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reconciliation_case")
public class ReconciliationCase {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "product_id", nullable = false) private UUID productId;
  @Column(name = "location_id", nullable = false) private UUID locationId;
  @Column(name = "expected_stock", nullable = false) private BigDecimal expectedStock;
  @Column(name = "actual_stock", nullable = false) private BigDecimal actualStock;
  @Column(name = "mismatch_quantity", nullable = false) private BigDecimal mismatchQuantity;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false) private ReconciliationSeverity severity;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false) private ReconciliationStatus status;
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "likely_causes", nullable = false, columnDefinition = "jsonb") private String likelyCauses;
  @Column(name = "calculated_at", nullable = false) private Instant calculatedAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected ReconciliationCase() {}

  public ReconciliationCase(UUID tenantId, UUID productId, UUID locationId, BigDecimal expectedStock, BigDecimal actualStock, BigDecimal mismatchQuantity, ReconciliationSeverity severity, String likelyCauses, Instant now) {
    this.tenantId = tenantId;
    this.productId = productId;
    this.locationId = locationId;
    this.status = ReconciliationStatus.OPEN;
    update(expectedStock, actualStock, mismatchQuantity, severity, likelyCauses, now);
    this.createdAt = now;
  }

  public boolean update(BigDecimal expectedStock, BigDecimal actualStock, BigDecimal mismatchQuantity, ReconciliationSeverity severity, String likelyCauses, Instant now) {
    boolean changed = this.expectedStock == null
        || this.expectedStock.compareTo(expectedStock) != 0
        || this.actualStock.compareTo(actualStock) != 0
        || this.mismatchQuantity.compareTo(mismatchQuantity) != 0
        || this.severity != severity;
    this.expectedStock = expectedStock;
    this.actualStock = actualStock;
    this.mismatchQuantity = mismatchQuantity;
    this.severity = severity;
    this.likelyCauses = likelyCauses;
    this.calculatedAt = now;
    this.updatedAt = now;
    return changed;
  }

  public void setStatus(ReconciliationStatus status, Instant now) {
    this.status = status;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getProductId() { return productId; }
  public UUID getLocationId() { return locationId; }
  public BigDecimal getExpectedStock() { return expectedStock; }
  public BigDecimal getActualStock() { return actualStock; }
  public BigDecimal getMismatchQuantity() { return mismatchQuantity; }
  public ReconciliationSeverity getSeverity() { return severity; }
  public ReconciliationStatus getStatus() { return status; }
  public String getLikelyCauses() { return likelyCauses; }
  public Instant getCalculatedAt() { return calculatedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
