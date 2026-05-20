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
@Table(name = "validation_report")
public class ValidationReport {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "import_job_id", nullable = false) private UUID importJobId;
  @Column(nullable = false) private String status;
  @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String summary;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected ValidationReport() {}

  public ValidationReport(UUID tenantId, UUID importJobId, String status, String summary, Instant createdAt) {
    this.tenantId = tenantId;
    this.importJobId = importJobId;
    this.status = status;
    this.summary = summary;
    this.createdAt = createdAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getImportJobId() { return importJobId; }
  public String getStatus() { return status; }
  public String getSummary() { return summary; }
}
