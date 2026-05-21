package com.orderpilot.domain.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "import_validation_issue")
public class ImportValidationIssue {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "import_job_id", nullable = false) private UUID importJobId;
  @Column(name = "import_staging_row_id") private UUID importStagingRowId;
  @Column(name = "row_number") private Integer rowNumber;
  @Column(nullable = false) private String severity;
  @Column(name = "issue_code", nullable = false) private String issueCode;
  @Column(nullable = false) private String message;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected ImportValidationIssue() {}

  public ImportValidationIssue(UUID tenantId, UUID importJobId, UUID importStagingRowId, Integer rowNumber, String severity, String issueCode, String message, Instant now) {
    this.tenantId = tenantId;
    this.importJobId = importJobId;
    this.importStagingRowId = importStagingRowId;
    this.rowNumber = rowNumber;
    this.severity = severity;
    this.issueCode = issueCode;
    this.message = message;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getImportJobId() { return importJobId; }
  public UUID getImportStagingRowId() { return importStagingRowId; }
  public Integer getRowNumber() { return rowNumber; }
  public String getSeverity() { return severity; }
  public String getIssueCode() { return issueCode; }
  public String getMessage() { return message; }
}