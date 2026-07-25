package com.orderpilot.api.dto;

import java.time.Instant;

/** Bounded request/response contracts for deployment lifecycle control. */
public final class ControlLifecycleDtos {
  private ControlLifecycleDtos() {}

  /** Staff backup-request intent; operation type, principal, and state remain backend-owned. */
  public record BackupRequest(String idempotencyKey) {}

  /** Executor stage request. Backend allocates artifact handle and canonical storage key. */
  public record StageRequest(Long fencingToken) {}

  /**
   * Executor terminal report. Success binds the backend-issued artifact handle and storage key with
   * closed provenance metadata; failure may omit the handle so the backend resolves the current staged
   * artifact.
   */
  public record CompleteRequest(
      Long fencingToken,
      String resultCode,
      String artifactHandle,
      String storageKey,
      String encryptionAlgorithm,
      String encryptionEnvelopeVersion,
      String encryptionKeyIdentifier,
      String postgresServerVersion,
      String pgDumpVersion,
      String pgRestoreVersion,
      String schemaVersion,
      Long encryptedByteSize,
      String ciphertextSha256,
      Boolean archiveValidated,
      Integer archiveEntryCount) {}

  /** Bounded operation view for staff request/read routes. No internal id, hash, or fingerprints. */
  public record OperationView(
      String operationId,
      String operationType,
      String state,
      String resultCode,
      int attempt,
      Instant createdAt,
      Instant updatedAt) {}

  /** Executor lease grant. Carries exactly what the executor needs to later report terminal work. */
  public record LeaseResponse(
      String operationId,
      String operationType,
      long fencingToken,
      Instant leaseExpiresAt) {}

  /** Backend-issued staged artifact identity for the current execution attempt. */
  public record StageResponse(
      String operationId,
      String artifactHandle,
      String storageKey,
      long fencingToken) {}

  /** Bounded terminal completion result for the executor. */
  public record CompletionResponse(String operationId, String state, String resultCode) {}

  /** Bounded, machine-readable error body. */
  public record ControlLifecycleError(String code) {}
}