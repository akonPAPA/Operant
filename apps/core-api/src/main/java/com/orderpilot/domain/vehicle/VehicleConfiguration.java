package com.orderpilot.domain.vehicle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicle_configuration")
public class VehicleConfiguration {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "vehicle_year_id", nullable = false) private UUID vehicleYearId;
  @Column(name = "configuration_code", nullable = false) private String configurationCode;
  private String engine;
  private String drivetrain;
  private String transmission;
  private String notes;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected VehicleConfiguration() {}
  public VehicleConfiguration(UUID tenantId, UUID vehicleYearId, String configurationCode, String engine, String drivetrain, String transmission, String notes, Instant now) {
    this.tenantId = tenantId; this.vehicleYearId = vehicleYearId; this.configurationCode = configurationCode; this.engine = engine; this.drivetrain = drivetrain; this.transmission = transmission; this.notes = notes; this.createdAt = now; this.updatedAt = now;
  }
  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getVehicleYearId() { return vehicleYearId; }
  public String getConfigurationCode() { return configurationCode; }
}