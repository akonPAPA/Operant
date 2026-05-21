package com.orderpilot.domain.vehicle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicle_model")
public class VehicleModel {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "vehicle_make_id", nullable = false) private UUID vehicleMakeId;
  @Column(nullable = false) private String code;
  @Column(nullable = false) private String name;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected VehicleModel() {}
  public VehicleModel(UUID tenantId, UUID vehicleMakeId, String code, String name, Instant now) { this.tenantId = tenantId; this.vehicleMakeId = vehicleMakeId; this.code = code; this.name = name; this.createdAt = now; this.updatedAt = now; }
  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getVehicleMakeId() { return vehicleMakeId; }
  public String getCode() { return code; }
  public String getName() { return name; }
}