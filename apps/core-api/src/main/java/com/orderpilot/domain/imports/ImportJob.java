package com.orderpilot.domain.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "import_job")
public class ImportJob {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "data_source_id") private UUID dataSourceId;
  @Column(name = "import_type", nullable = false) private String importType;
  @Column(name = "original_filename") private String originalFilename;
  @Column(nullable = false) private String status;
  @Column(name = "total_rows", nullable = false) private int totalRows;
  @Column(name = "valid_rows", nullable = false) private int validRows;
  @Column(name = "invalid_rows", nullable = false) private int invalidRows;
  @Column(name = "error_summary") private String errorSummary;
  @Column(name = "created_by") private UUID createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected ImportJob() {}

  public ImportJob(UUID tenantId, UUID dataSourceId, String importType, String originalFilename, UUID createdBy, Instant now) {
    this.tenantId = tenantId;
    this.dataSourceId = dataSourceId;
    this.importType = importType;
    this.originalFilename = originalFilename;
    this.status = "CREATED";
    this.createdBy = createdBy;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void markStaged(int totalRows, Instant now) {
    this.totalRows = totalRows;
    this.status = "STAGED";
    this.updatedAt = now;
  }

  public void markValidating(Instant now) {
    this.status = "VALIDATING";
    this.errorSummary = null;
    this.updatedAt = now;
  }

  public void markValidated(int totalRows, int validRows, int invalidRows, Instant now) {
    this.totalRows = totalRows;
    this.validRows = validRows;
    this.invalidRows = invalidRows;
    this.status = "VALIDATED";
    this.errorSummary = invalidRows == 0 ? null : invalidRows + " row(s) failed validation";
    this.updatedAt = now;
  }

  public void markFailed(String reason, Instant now) {
    this.status = "FAILED";
    this.errorSummary = reason;
    this.updatedAt = now;
  }

  public void markApplied(Instant now) {
    this.status = "APPLIED";
    this.updatedAt = now;
  }

  public void markRejected(String reason, Instant now) {
    this.status = "REJECTED";
    this.errorSummary = reason;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getImportType() { return importType; }
  public String getOriginalFilename() { return originalFilename; }
  public String getStatus() { return status; }
  public int getTotalRows() { return totalRows; }
  public int getValidRows() { return validRows; }
  public int getInvalidRows() { return invalidRows; }
  public String getErrorSummary() { return errorSummary; }
  public Instant getCreatedAt() { return createdAt; }
}
