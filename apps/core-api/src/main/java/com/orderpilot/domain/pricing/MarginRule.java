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
@Table(name = "margin_rule")
public class MarginRule {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(nullable = false) private String code;
  @Column(nullable = false) private String name;
  @Column(name = "product_id") private UUID productId;
  private String category;
  @Column(name = "customer_segment_id") private UUID customerSegmentId;
  @Column(name = "minimum_gross_margin_percent", nullable = false) private BigDecimal minimumGrossMarginPercent;
  @Column(name = "approval_required_below_percent", nullable = false) private BigDecimal approvalRequiredBelowPercent;
  @Column(nullable = false) private boolean active;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected MarginRule() {}
  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getProductId() { return productId; }
  public String getCategory() { return category; }
  public UUID getCustomerSegmentId() { return customerSegmentId; }
  public BigDecimal getMinimumGrossMarginPercent() { return minimumGrossMarginPercent; }
  public BigDecimal getApprovalRequiredBelowPercent() { return approvalRequiredBelowPercent; }
  public boolean isActive() { return active; }
}
