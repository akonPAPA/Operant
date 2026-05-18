package com.orderpilot.domain.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "import_staging_row")
public class ImportStagingRow {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "import_job_id", nullable = false) private UUID importJobId;
  @Column(name = "row_number", nullable = false) private int rowNumber;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "raw_data", nullable = false, columnDefinition = "jsonb") private String rawData;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "mapped_data", nullable = false, columnDefinition = "jsonb") private String mappedData;
  @Column(name = "validation_status", nullable = false) private String validationStatus;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "validation_errors", columnDefinition = "jsonb") private String validationErrors;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected ImportStagingRow() {}

  public ImportStagingRow(UUID tenantId, UUID importJobId, int rowNumber, String rawData, Instant now) {
    this.tenantId = tenantId;
    this.importJobId = importJobId;
    this.rowNumber = rowNumber;
    this.rawData = rawData == null || rawData.isBlank() ? "{}" : rawData;
    this.mappedData = "{}";
    this.validationStatus = "PENDING";
    this.createdAt = now;
  }

  public void setValidation(String validationStatus, String mappedData, String validationErrors) {
    this.validationStatus = validationStatus;
    this.mappedData = mappedData == null || mappedData.isBlank() ? "{}" : mappedData;
    this.validationErrors = validationErrors;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getImportJobId() { return importJobId; }
  public int getRowNumber() { return rowNumber; }
  public String getRawData() { return rawData; }
  public String getMappedData() { return mappedData; }
  public String getValidationStatus() { return validationStatus; }
  public String getValidationErrors() { return validationErrors; }
}