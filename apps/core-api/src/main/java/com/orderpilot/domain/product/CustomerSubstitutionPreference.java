package com.orderpilot.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_substitution_preference")
public class CustomerSubstitutionPreference {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "customer_account_id", nullable = false) private UUID customerAccountId;
  @Column(name = "product_id") private UUID productId;
  private String brand;
  @Column(name = "allow_aftermarket", nullable = false) private boolean allowAftermarket;
  @Column(name = "allow_used") private Boolean allowUsed;
  @Column(name = "blocked_substitute_product_id") private UUID blockedSubstituteProductId;
  private String notes;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected CustomerSubstitutionPreference() {}
}