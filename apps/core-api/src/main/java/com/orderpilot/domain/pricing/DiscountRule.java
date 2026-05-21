package com.orderpilot.domain.pricing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "discount_rule")
public class DiscountRule {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(nullable = false) private String code;
  @Column(nullable = false) private String name;
  @Column(name = "customer_account_id") private UUID customerAccountId;
  @Column(name = "customer_segment_id") private UUID customerSegmentId;
  @Column(name = "product_id") private UUID productId;
  @Column(name = "max_discount_percent", nullable = false) private BigDecimal maxDiscountPercent;
  @Column(name = "requires_approval_above_percent", nullable = false) private BigDecimal requiresApprovalAbovePercent;
  @Column(name = "active_from", nullable = false) private Instant activeFrom;
  @Column(name = "active_to") private Instant activeTo;
  @Column(nullable = false) private boolean active;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected DiscountRule() {}
  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getCode() { return code; }
  public String getName() { return name; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public UUID getCustomerSegmentId() { return customerSegmentId; }
  public UUID getProductId() { return productId; }
  public BigDecimal getMaxDiscountPercent() { return maxDiscountPercent; }
  public BigDecimal getRequiresApprovalAbovePercent() { return requiresApprovalAbovePercent; }
  public Instant getActiveFrom() { return activeFrom; }
  public Instant getActiveTo() { return activeTo; }
  public boolean isActive() { return active; }
}
