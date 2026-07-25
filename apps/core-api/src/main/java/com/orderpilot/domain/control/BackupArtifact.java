package com.orderpilot.domain.control;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(
    name = "backup_artifact",
    uniqueConstraints = {
        @UniqueConstraint(name = "ux_backup_artifact_public_handle", columnNames = "public_handle"),
        @UniqueConstraint(name = "ux_backup_artifact_storage_key", columnNames = "storage_key"),
        @UniqueConstraint(
            name = "ux_backup_artifact_execution_identity",
            columnNames = {"lifecycle_operation_id", "execution_attempt", "fencing_token"})
    },
    indexes = {
        @Index(name = "idx_backup_artifact_lifecycle_operation", columnList = "lifecycle_operation_id"),
        @Index(name = "idx_backup_artifact_state_created", columnList = "state, created_at")
    })
public class BackupArtifact {
  public static final String POSTGRES_CUSTOM_FORMAT = "POSTGRES_CUSTOM";
  public static final String ENCRYPTION_ALGORITHM_AES_256_GCM = "AES-256-GCM";
  public static final String ENCRYPTION_ENVELOPE_VERSION_V1 = "v1";

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PUBLIC_HANDLE = Pattern.compile("ba_[0-9a-f]{24}");
  private static final Pattern SAFE_STORAGE_KEY =
      Pattern.compile("^(?!/)(?![A-Za-z]:)(?!.*\\\\)(?!.*(^|/)\\.\\.(/|$)).{1,256}$");
  private static final Pattern SAFE_KEY_IDENTIFIER = Pattern.compile("[0-9A-Za-z:_-]{1,80}");

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "public_handle", nullable = false, updatable = false, length = 48)
  private String publicHandle;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "lifecycle_operation_id", nullable = false, updatable = false)
  private LifecycleOperation lifecycleOperation;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 20)
  private BackupArtifactState state;

  @Column(name = "backup_format", nullable = false, length = 40)
  private String backupFormat;

  @Column(name = "encryption_algorithm", length = 40)
  private String encryptionAlgorithm;

  @Column(name = "encryption_envelope_version", length = 40)
  private String encryptionEnvelopeVersion;

  @Column(name = "encryption_key_identifier", length = 80)
  private String encryptionKeyIdentifier;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "available_at")
  private Instant availableAt;

  @Column(name = "postgres_server_version", length = 80)
  private String postgresServerVersion;

  @Column(name = "pg_dump_version", length = 80)
  private String pgDumpVersion;

  @Column(name = "pg_restore_version", length = 80)
  private String pgRestoreVersion;

  @Column(name = "schema_version", length = 40)
  private String schemaVersion;

  @Column(name = "encrypted_byte_size")
  private Long encryptedByteSize;

  @Column(name = "ciphertext_sha256", length = 64)
  private String ciphertextSha256;

  @Column(name = "archive_validated")
  private Boolean archiveValidated;

  @Column(name = "archive_entry_count")
  private Integer archiveEntryCount;

  @Column(name = "storage_key", nullable = false, updatable = false, length = 256)
  private String storageKey;

  @Column(name = "execution_attempt", nullable = false, updatable = false)
  private Integer executionAttempt;

  @Column(name = "fencing_token", nullable = false, updatable = false)
  private Long fencingToken;

  @Column(name = "failure_code", length = 80)
  private String failureCode;

  protected BackupArtifact() {
    // JPA
  }

  private BackupArtifact(
      String publicHandle,
      LifecycleOperation lifecycleOperation,
      String backupFormat,
      String storageKey,
      Integer executionAttempt,
      Long fencingToken,
      Instant now) {
    this.publicHandle = requirePattern(publicHandle, "publicHandle", PUBLIC_HANDLE);
    this.lifecycleOperation = Objects.requireNonNull(lifecycleOperation, "lifecycleOperation");
    this.state = BackupArtifactState.STAGED;
    this.backupFormat = requireText(backupFormat, "backupFormat");
    this.storageKey = requirePattern(storageKey, "storageKey", SAFE_STORAGE_KEY);
    this.executionAttempt = requirePositive(executionAttempt, "executionAttempt");
    this.fencingToken = requirePositive(fencingToken, "fencingToken");
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public static BackupArtifact staged(
      String publicHandle,
      LifecycleOperation lifecycleOperation,
      String backupFormat,
      String storageKey,
      Integer executionAttempt,
      Long fencingToken,
      Instant now) {
    return new BackupArtifact(
        publicHandle, lifecycleOperation, backupFormat, storageKey, executionAttempt, fencingToken, now);
  }

  public void markAvailable(AvailableMetadata metadata, Instant now) {
    if (state != BackupArtifactState.STAGED) {
      throw new IllegalStateException("ONLY_STAGED_ARTIFACT_CAN_BECOME_AVAILABLE");
    }
    AvailableMetadata safe = Objects.requireNonNull(metadata, "metadata");
    if (safe.encryptedByteSize() <= 0) {
      throw new IllegalArgumentException("ENCRYPTED_SIZE_REQUIRED");
    }
    if (!SHA256_HEX.matcher(requireText(safe.ciphertextSha256(), "ciphertextSha256")).matches()) {
      throw new IllegalArgumentException("CIPHERTEXT_SHA256_INVALID");
    }
    if (!safe.archiveValidated()) {
      throw new IllegalArgumentException("ARCHIVE_VALIDATION_REQUIRED");
    }
    if (safe.archiveEntryCount() <= 0) {
      throw new IllegalArgumentException("ARCHIVE_ENTRY_COUNT_INVALID");
    }
    this.encryptionAlgorithm = requireExact(
        safe.encryptionAlgorithm(), ENCRYPTION_ALGORITHM_AES_256_GCM, "encryptionAlgorithm");
    this.encryptionEnvelopeVersion = requireExact(
        safe.encryptionEnvelopeVersion(), ENCRYPTION_ENVELOPE_VERSION_V1, "envelopeVersion");
    this.encryptionKeyIdentifier = requirePattern(
        safe.encryptionKeyIdentifier(), "keyIdentifier", SAFE_KEY_IDENTIFIER);
    this.postgresServerVersion = requireBounded(safe.postgresServerVersion(), "postgresServerVersion", 80);
    this.pgDumpVersion = requireBounded(safe.pgDumpVersion(), "pgDumpVersion", 80);
    this.pgRestoreVersion = requireBounded(safe.pgRestoreVersion(), "pgRestoreVersion", 80);
    this.schemaVersion = requireBounded(safe.schemaVersion(), "schemaVersion", 40);
    this.encryptedByteSize = safe.encryptedByteSize();
    this.ciphertextSha256 = safe.ciphertextSha256();
    this.archiveValidated = true;
    this.archiveEntryCount = safe.archiveEntryCount();
    this.availableAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
    this.state = BackupArtifactState.AVAILABLE;
  }

  public void reject(BackupArtifactFailureCode failureCode, Instant now) {
    markNonAuthoritative(BackupArtifactState.REJECTED, failureCode, now);
  }

  public void markOrphaned(BackupArtifactFailureCode failureCode, Instant now) {
    markNonAuthoritative(BackupArtifactState.ORPHANED, failureCode, now);
  }

  private void markNonAuthoritative(
      BackupArtifactState nextState, BackupArtifactFailureCode failureCode, Instant now) {
    if (state != BackupArtifactState.STAGED) {
      throw new IllegalStateException("ONLY_STAGED_ARTIFACT_CAN_BECOME_TERMINAL");
    }
    this.state = nextState;
    this.failureCode = Objects.requireNonNull(failureCode, "failureCode").name();
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + "_REQUIRED");
    }
    return value;
  }

  private static String requirePattern(String value, String field, Pattern pattern) {
    String safe = requireText(value, field);
    if (!pattern.matcher(safe).matches()) {
      throw new IllegalArgumentException(field + "_INVALID");
    }
    return safe;
  }

  private static String requireExact(String value, String expected, String field) {
    String safe = requireText(value, field);
    if (!expected.equals(safe)) {
      throw new IllegalArgumentException(field + "_INVALID");
    }
    return safe;
  }

  private static int requirePositive(Integer value, String field) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(field + "_INVALID");
    }
    return value;
  }

  private static long requirePositive(Long value, String field) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(field + "_INVALID");
    }
    return value;
  }

  private static String requireBounded(String value, String field, int max) {
    String safe = requireText(value, field);
    if (safe.length() > max) {
      throw new IllegalArgumentException(field + "_TOO_LONG");
    }
    return safe;
  }

  public UUID getId() {
    return id;
  }

  public String getPublicHandle() {
    return publicHandle;
  }

  public LifecycleOperation getLifecycleOperation() {
    return lifecycleOperation;
  }

  public BackupArtifactState getState() {
    return state;
  }

  public boolean isAuthoritative() {
    return state.isAuthoritative();
  }

  public boolean matchesStagedIdentity(String handle, String storageKey) {
    return Objects.equals(publicHandle, handle) && Objects.equals(this.storageKey, storageKey);
  }

  public boolean matchesAvailableMetadata(AvailableMetadata metadata) {
    AvailableMetadata safe = Objects.requireNonNull(metadata, "metadata");
    return state == BackupArtifactState.AVAILABLE
        && Objects.equals(encryptionAlgorithm, safe.encryptionAlgorithm())
        && Objects.equals(encryptionEnvelopeVersion, safe.encryptionEnvelopeVersion())
        && Objects.equals(encryptionKeyIdentifier, safe.encryptionKeyIdentifier())
        && Objects.equals(postgresServerVersion, safe.postgresServerVersion())
        && Objects.equals(pgDumpVersion, safe.pgDumpVersion())
        && Objects.equals(pgRestoreVersion, safe.pgRestoreVersion())
        && Objects.equals(schemaVersion, safe.schemaVersion())
        && Objects.equals(encryptedByteSize, safe.encryptedByteSize())
        && Objects.equals(ciphertextSha256, safe.ciphertextSha256())
        && Objects.equals(archiveValidated, safe.archiveValidated())
        && Objects.equals(archiveEntryCount, safe.archiveEntryCount());
  }

  public String getBackupFormat() {
    return backupFormat;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public String getEncryptionAlgorithm() {
    return encryptionAlgorithm;
  }

  public String getEncryptionEnvelopeVersion() {
    return encryptionEnvelopeVersion;
  }

  public String getEncryptionKeyIdentifier() {
    return encryptionKeyIdentifier;
  }

  public String getPostgresServerVersion() {
    return postgresServerVersion;
  }

  public String getPgDumpVersion() {
    return pgDumpVersion;
  }

  public String getPgRestoreVersion() {
    return pgRestoreVersion;
  }

  public String getSchemaVersion() {
    return schemaVersion;
  }

  public Integer getExecutionAttempt() {
    return executionAttempt;
  }

  public Long getFencingToken() {
    return fencingToken;
  }

  public Long getEncryptedByteSize() {
    return encryptedByteSize;
  }

  public String getCiphertextSha256() {
    return ciphertextSha256;
  }

  public Boolean getArchiveValidated() {
    return archiveValidated;
  }

  public Integer getArchiveEntryCount() {
    return archiveEntryCount;
  }

  public Instant getAvailableAt() {
    return availableAt;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public record AvailableMetadata(
      String encryptionAlgorithm,
      String encryptionEnvelopeVersion,
      String encryptionKeyIdentifier,
      String postgresServerVersion,
      String pgDumpVersion,
      String pgRestoreVersion,
      String schemaVersion,
      long encryptedByteSize,
      String ciphertextSha256,
      boolean archiveValidated,
      int archiveEntryCount) {}
}