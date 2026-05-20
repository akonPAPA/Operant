package com.orderpilot.domain.location;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "location")
public class Location {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(nullable = false) private String code;
  @Column(nullable = false) private String name;
  @Column(nullable = false) private String type;
  private String address;
  private String city;
  private String country;
  @Column(nullable = false) private boolean active;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected Location() {}

  public Location(UUID tenantId, String code, String name, String type, String address, String city, String country, boolean active, Instant now) {
    this.tenantId = tenantId;
    this.code = code;
    this.name = name;
    this.type = type;
    this.address = address;
    this.city = city;
    this.country = country;
    this.active = active;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getCode() { return code; }
  public String getName() { return name; }
  public String getType() { return type; }
  public boolean isActive() { return active; }
}
