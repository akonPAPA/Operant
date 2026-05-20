package com.orderpilot.domain.pricing;

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
@Table(name = "price_rule")
public class PriceRule {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "product_id", nullable = false) private UUID productId;
  @Column(name = "customer_account_id") private UUID customerAccountId;
  @Column(name = "customer_segment_id") private UUID customerSegmentId;
  @Column(name = "location_id") private UUID locationId;
  @Column(name = "min_quantity", nullable = false) private BigDecimal minQuantity;
  @Column(nullable = false) private String uom;
  @Column(name = "unit_price", nullable = false) private BigDecimal unitPrice;
  @Size(min = 3, max = 3)
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3, columnDefinition = "char(3)") private String currency;
  @Column(name = "active_from", nullable = false) private Instant activeFrom;
  @Column(name = "active_to") private Instant activeTo;
  @Column(nullable = false) private int priority;
  @Column(nullable = false) private boolean active;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected PriceRule() {}

  public PriceRule(UUID tenantId, UUID productId, UUID customerAccountId, UUID customerSegmentId, UUID locationId, BigDecimal minQuantity, String uom, BigDecimal unitPrice, String currency, Instant activeFrom, Instant activeTo, Integer priority, Instant now) {
    this.tenantId = tenantId;
    this.productId = productId;
    this.customerAccountId = customerAccountId;
    this.customerSegmentId = customerSegmentId;
    this.locationId = locationId;
    this.minQuantity = minQuantity;
    this.uom = uom;
    this.unitPrice = unitPrice;
    this.currency = currency;
    this.activeFrom = activeFrom;
    this.activeTo = activeTo;
    this.priority = priority == null ? 100 : priority;
    this.active = true;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getProductId() { return productId; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public UUID getCustomerSegmentId() { return customerSegmentId; }
  public UUID getLocationId() { return locationId; }
  public BigDecimal getMinQuantity() { return minQuantity; }
  public String getUom() { return uom; }
  public BigDecimal getUnitPrice() { return unitPrice; }
  public String getCurrency() { return currency; }
  public Instant getActiveFrom() { return activeFrom; }
  public Instant getActiveTo() { return activeTo; }
  public int getPriority() { return priority; }
  public boolean isActive() { return active; }
}
