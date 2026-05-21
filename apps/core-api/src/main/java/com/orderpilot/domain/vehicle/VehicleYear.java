package com.orderpilot.domain.vehicle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicle_year")
public class VehicleYear {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "vehicle_model_id", nullable = false) private UUID vehicleModelId;
  @Column(name = "model_year", nullable = false) private Integer modelYear;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected VehicleYear() {}
  public VehicleYear(UUID tenantId, UUID vehicleModelId, Integer modelYear, Instant now) { this.tenantId = tenantId; this.vehicleModelId = vehicleModelId; this.modelYear = modelYear; this.createdAt = now; this.updatedAt = now; }
  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getVehicleModelId() { return vehicleModelId; }
  public Integer getModelYear() { return modelYear; }
}