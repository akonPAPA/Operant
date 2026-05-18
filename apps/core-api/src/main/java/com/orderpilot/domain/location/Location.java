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
}