package com.orderpilot.api.rest;

import com.orderpilot.api.dto.ControlLifecycleDtos.BackupRequest;
import com.orderpilot.api.dto.ControlLifecycleDtos.CompleteRequest;
import com.orderpilot.api.dto.ControlLifecycleDtos.CompletionResponse;
import com.orderpilot.api.dto.ControlLifecycleDtos.ControlLifecycleError;
import com.orderpilot.api.dto.ControlLifecycleDtos.LeaseResponse;
import com.orderpilot.api.dto.ControlLifecycleDtos.OperationView;
import com.orderpilot.api.dto.ControlLifecycleDtos.StageRequest;
import com.orderpilot.api.dto.ControlLifecycleDtos.StageResponse;
import com.orderpilot.application.services.control.lifecycle.BackupArtifactPersistenceService;
import com.orderpilot.application.services.control.lifecycle.BackupArtifactPersistenceService.FinalizeReportCommand;
import com.orderpilot.application.services.control.lifecycle.BackupArtifactPersistenceService.StageArtifactCommand;
import com.orderpilot.application.services.control.lifecycle.LifecycleBackupOperationService;
import com.orderpilot.application.services.control.lifecycle.LifecycleControlException;
import com.orderpilot.application.services.control.lifecycle.PostgresToolVersionNormalizer;
import com.orderpilot.application.services.control.lifecycle.PostgresToolVersionNormalizer.ExpectedPostgresTool;
import com.orderpilot.domain.control.BackupArtifact;
import com.orderpilot.domain.control.BackupArtifact.AvailableMetadata;
import com.orderpilot.domain.control.LifecycleOperation;
import com.orderpilot.domain.control.LifecycleOperationResultCode;
import com.orderpilot.security.ControlPlanePrincipal;
import com.orderpilot.security.ControlPlanePrincipalFingerprint;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Bounded deployment lifecycle control surface under /api/v1/internal/control/lifecycle. */
@RestController
public class InternalControlLifecycleController {
  private static final String BASE = "/api/v1/internal/control/lifecycle";

  private final LifecycleBackupOperationService service;
  private final BackupArtifactPersistenceService artifactService;

  public InternalControlLifecycleController(
      LifecycleBackupOperationService service,
      BackupArtifactPersistenceService artifactService) {
    this.service = service;
    this.artifactService = artifactService;
  }

  @PostMapping(BASE + "/backups")
  public ResponseEntity<OperationView> requestBackup(
      @RequestBody(required = false) BackupRequest request) {
    String idempotencyKey = request == null ? null : request.idempotencyKey();
    LifecycleOperation operation = service.requestBackup(currentFingerprint(), idempotencyKey);
    return ResponseEntity.accepted().body(view(operation));
  }

  @GetMapping(BASE + "/operations/{operationId}")
  public ResponseEntity<OperationView> getOperation(@PathVariable String operationId) {
    return service.findByPublicId(operationId)
        .map(operation -> ResponseEntity.ok(view(operation)))
        .orElseThrow(LifecycleControlException.OperationNotFound::new);
  }

  @PostMapping(BASE + "/executor/lease")
  public ResponseEntity<LeaseResponse> lease() {
    Optional<LifecycleOperation> leased = service.leaseNext(currentFingerprint());
    return leased
        .map(operation -> ResponseEntity.ok(new LeaseResponse(
            operation.getPublicId(),
            operation.getOperationType().name(),
            operation.getFencingToken(),
            operation.getLeaseExpiresAt())))
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PostMapping(BASE + "/operations/{operationId}/artifacts/stage")
  public ResponseEntity<StageResponse> stage(
      @PathVariable String operationId,
      @RequestBody(required = false) StageRequest request) {
    if (request == null || request.fencingToken() == null) {
      throw new LifecycleControlException.InvalidRequest("FENCING_TOKEN_REQUIRED");
    }
    BackupArtifact artifact = artifactService.stageArtifact(new StageArtifactCommand(
        operationId, currentFingerprint(), request.fencingToken()));
    return ResponseEntity.ok(new StageResponse(
        operationId,
        artifact.getPublicHandle(),
        artifact.getStorageKey(),
        request.fencingToken()));
  }

  @PostMapping(BASE + "/operations/{operationId}/complete")
  public ResponseEntity<CompletionResponse> complete(
      @PathVariable String operationId,
      @RequestBody(required = false) CompleteRequest request) {
    if (request == null || request.fencingToken() == null) {
      throw new LifecycleControlException.InvalidRequest("FENCING_TOKEN_REQUIRED");
    }
    LifecycleOperationResultCode resultCode = LifecycleOperationResultCode.parse(request.resultCode())
        .orElseThrow(() -> new LifecycleControlException.InvalidRequest("INVALID_RESULT_CODE"));
    LifecycleOperation operation = artifactService.completeReport(new FinalizeReportCommand(
        operationId,
        currentFingerprint(),
        request.fencingToken(),
        request.artifactHandle(),
        request.storageKey(),
        resultCode,
        resultCode == LifecycleOperationResultCode.BACKUP_COMPLETED ? metadata(request) : null));
    return ResponseEntity.ok(new CompletionResponse(
        operation.getPublicId(),
        operation.getState().name(),
        operation.getResultCode() == null ? null : operation.getResultCode().name()));
  }

  @ExceptionHandler(LifecycleControlException.class)
  public ResponseEntity<ControlLifecycleError> handle(LifecycleControlException exception) {
    return ResponseEntity.status(statusFor(exception))
        .body(new ControlLifecycleError(exception.reasonCode()));
  }

  /**
   * A domain/boundary validation failure of the executor's artifact report is a bounded client-facing
   * 400 with a fixed non-enumerating code (never the raw exception message). NullPointerException is
   * deliberately NOT handled here: an unexpected NPE is an internal programming defect and must fall
   * through to the redacted global 500 contract rather than be disguised as an invalid client report.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ControlLifecycleError> handleInvalidRuntimeContract(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(new ControlLifecycleError("INVALID_BACKUP_ARTIFACT_REPORT"));
  }

  private static HttpStatus statusFor(LifecycleControlException exception) {
    if (exception instanceof LifecycleControlException.ExecutorDisabled) {
      return HttpStatus.SERVICE_UNAVAILABLE;
    }
    if (exception instanceof LifecycleControlException.InvalidRequest) {
      return HttpStatus.BAD_REQUEST;
    }
    if (exception instanceof LifecycleControlException.OperationNotFound) {
      return HttpStatus.NOT_FOUND;
    }
    return HttpStatus.CONFLICT;
  }

  private static AvailableMetadata metadata(CompleteRequest request) {
    if (request.artifactHandle() == null || request.artifactHandle().isBlank()) {
      throw new LifecycleControlException.InvalidRequest("ARTIFACT_HANDLE_REQUIRED");
    }
    if (request.storageKey() == null || request.storageKey().isBlank()) {
      throw new LifecycleControlException.InvalidRequest("ARTIFACT_STORAGE_KEY_REQUIRED");
    }
    if (request.encryptedByteSize() == null
        || request.archiveValidated() == null
        || request.archiveEntryCount() == null) {
      throw new LifecycleControlException.InvalidRequest("ARTIFACT_METADATA_REQUIRED");
    }
    // Authoritative, provenance-preserving boundary normalization: the executor may report either a
    // canonical version or the recognised CLI banner that THAT specific tool emits. Each field is
    // normalized against its own tool identity, so a psql/pg_restore/server banner can never be accepted
    // in the pg_dump field (and vice versa). Anything ambiguous or foreign fail-closes to
    // IllegalArgumentException -> bounded 400 INVALID_BACKUP_ARTIFACT_REPORT (never the raw banner).
    return new AvailableMetadata(
        request.encryptionAlgorithm(),
        request.encryptionEnvelopeVersion(),
        request.encryptionKeyIdentifier(),
        PostgresToolVersionNormalizer.normalize(
            ExpectedPostgresTool.POSTGRES_SERVER, request.postgresServerVersion()),
        PostgresToolVersionNormalizer.normalize(
            ExpectedPostgresTool.PG_DUMP, request.pgDumpVersion()),
        PostgresToolVersionNormalizer.normalize(
            ExpectedPostgresTool.PG_RESTORE, request.pgRestoreVersion()),
        request.schemaVersion(),
        request.encryptedByteSize(),
        request.ciphertextSha256(),
        request.archiveValidated(),
        request.archiveEntryCount());
  }

  private static OperationView view(LifecycleOperation operation) {
    return new OperationView(
        operation.getPublicId(),
        operation.getOperationType().name(),
        operation.getState().name(),
        operation.getResultCode() == null ? null : operation.getResultCode().name(),
        operation.getAttempt(),
        operation.getCreatedAt(),
        operation.getUpdatedAt());
  }

  private static String currentFingerprint() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof ControlPlanePrincipal principal) {
      return ControlPlanePrincipalFingerprint.of(principal);
    }
    return ControlPlanePrincipalFingerprint.of(null);
  }
}
