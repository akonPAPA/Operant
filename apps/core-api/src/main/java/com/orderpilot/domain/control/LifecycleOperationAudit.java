package com.orderpilot.domain.control;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(
    name = "lifecycle_operation_audit",
    indexes = {
        @Index(
            name = "idx_lifecycle_operation_audit_operation_order",
            columnList = "lifecycle_operation_id, created_at, id"),
        @Index(
            name = "idx_lifecycle_operation_audit_artifact_order",
            columnList = "backup_artifact_id, created_at, id")
    })
public class LifecycleOperationAudit {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "lifecycle_operation_id", nullable = false, updatable = false)
  private LifecycleOperation lifecycleOperation;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "backup_artifact_id", updatable = false)
  private BackupArtifact backupArtifact;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, updatable = false, length = 80)
  private LifecycleOperationAuditEventType eventType;

  @Enumerated(EnumType.STRING)
  @Column(name = "principal_type", nullable = false, updatable = false, length = 30)
  private LifecycleOperationAuditPrincipalType principalType;

  @Column(name = "principal_fingerprint", nullable = false, updatable = false, length = 64)
  private String principalFingerprint;

  @Column(name = "result_code", updatable = false, length = 80)
  private String resultCode;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", nullable = false, updatable = false, columnDefinition = "jsonb")
  private String metadata;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected LifecycleOperationAudit() {
    // JPA
  }

  public LifecycleOperationAudit(
      LifecycleOperation lifecycleOperation,
      BackupArtifact backupArtifact,
      LifecycleOperationAuditEventType eventType,
      LifecycleOperationAuditPrincipalType principalType,
      String principalFingerprint,
      String resultCode,
      String metadata,
      Instant createdAt) {
    this.lifecycleOperation = Objects.requireNonNull(lifecycleOperation, "lifecycleOperation");
    this.backupArtifact = backupArtifact;
    this.eventType = Objects.requireNonNull(eventType, "eventType");
    this.principalType = Objects.requireNonNull(principalType, "principalType");
    this.principalFingerprint = requireText(principalFingerprint, "principalFingerprint");
    this.resultCode = blankToNull(resultCode);
    this.metadata = safeMetadata(metadata);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + "_REQUIRED");
    }
    if (value.length() > 64) {
      throw new IllegalArgumentException(field + "_TOO_LONG");
    }
    return value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String safeMetadata(String value) {
    String metadata = value == null || value.isBlank() ? "{}" : value;
    if (metadata.length() > 2048) {
      throw new IllegalArgumentException("AUDIT_METADATA_TOO_LONG");
    }
    return metadata;
  }

  public Long getId() {
    return id;
  }

  public LifecycleOperation getLifecycleOperation() {
    return lifecycleOperation;
  }

  public BackupArtifact getBackupArtifact() {
    return backupArtifact;
  }

  public LifecycleOperationAuditEventType getEventType() {
    return eventType;
  }

  public LifecycleOperationAuditPrincipalType getPrincipalType() {
    return principalType;
  }

  public String getPrincipalFingerprint() {
    return principalFingerprint;
  }

  public String getResultCode() {
    return resultCode;
  }

  public String getMetadata() {
    return metadata;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
