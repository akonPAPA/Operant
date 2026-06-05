package com.orderpilot.domain.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_contact")
public class CustomerContact {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "customer_account_id", nullable = false) private UUID customerAccountId;
  @Column(name = "contact_type", nullable = false) private String contactType;
  @Column(name = "full_name", nullable = false) private String fullName;
  private String email;
  private String phone;
  private String title;
  @Column(nullable = false) private boolean preferred;
  @Column(nullable = false) private boolean active;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Column(name = "deleted_at") private Instant deletedAt;

  protected CustomerContact() {}

  public CustomerContact(UUID tenantId, UUID customerAccountId, String contactType, String fullName, String email, String phone, String title, boolean preferred, Instant now) {
    this.tenantId = tenantId;
    this.customerAccountId = customerAccountId;
    this.contactType = contactType;
    this.fullName = fullName;
    this.email = email;
    this.phone = phone;
    this.title = title;
    this.preferred = preferred;
    this.active = true;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public String getContactType() { return contactType; }
  public String getFullName() { return fullName; }
  public String getEmail() { return email; }
  public String getPhone() { return phone; }
  public boolean isActive() { return active; }
  public boolean isPreferred() { return preferred; }
  public String getTitle() { return title; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}