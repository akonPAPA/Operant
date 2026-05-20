package com.orderpilot.domain.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant")
public class Tenant {
  @Id
  @GeneratedValue
  private UUID id;

  @Column(nullable = false, unique = true)
  private String slug;

  @Column(name = "legal_name", nullable = false)
  private String legalName;

  @Column(nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Tenant() {
  }

  public Tenant(String slug, String legalName, String status, Instant now) {
    this.slug = slug;
    this.legalName = legalName;
    this.status = status == null || status.isBlank() ? "ACTIVE" : status;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public String getSlug() { return slug; }
  public String getLegalName() { return legalName; }
  public String getStatus() { return status; }
}
