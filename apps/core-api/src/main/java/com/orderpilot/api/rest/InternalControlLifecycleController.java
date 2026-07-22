package com.orderpilot.api.rest;

import com.orderpilot.api.dto.ControlLifecycleDtos.BackupRequest;
import com.orderpilot.api.dto.ControlLifecycleDtos.CompleteRequest;
import com.orderpilot.api.dto.ControlLifecycleDtos.CompletionResponse;
import com.orderpilot.api.dto.ControlLifecycleDtos.ControlLifecycleError;
import com.orderpilot.api.dto.ControlLifecycleDtos.LeaseResponse;
import com.orderpilot.api.dto.ControlLifecycleDtos.OperationView;
import com.orderpilot.application.services.control.lifecycle.LifecycleBackupOperationService;
import com.orderpilot.application.services.control.lifecycle.LifecycleControlException;
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

/**
 * P1-E2A - the bounded durable-backup-operation control surface under
 * {@code /api/v1/internal/control/lifecycle/**}. Staff routes are reserved for an authenticated Operant
 * control client; executor routes are reserved for a dedicated lifecycle-executor service account.
 * Route-edge security is enforced by {@link com.orderpilot.security.ApiRouteSecurityPolicy}: each route
 * requires a distinct control permission, staff and executor permissions are disjoint, and tenant/support
 * permissions are denied.
 *
 * <p>This slice ends at the Core protocol boundary. It does not claim that a packaged operantctl backup
 * command or a real backup executor exists. Lease and completion record bounded control state only; no
 * pg_dump, artifact, restore, or filesystem operation is performed. The executor capability is disabled by
 * default, so a backup request fails closed before any operation is created.
 */
@RestController
public class InternalControlLifecycleController {
  private static final String BASE = "/api/v1/internal/control/lifecycle";

  private final LifecycleBackupOperationService service;

  public InternalControlLifecycleController(LifecycleBackupOperationService service) {
    this.service = service;
  }

  /**
   * Staff route: request a backup operation (STAFF_CONTROL_BACKUP). The opaque idempotency intent is in
   * the signed JSON body, so post-signature header tampering cannot alter deduplication semantics.
   */
  @PostMapping(BASE + "/backups")
  public ResponseEntity<OperationView> requestBackup(
      @RequestBody(required = false) BackupRequest request) {
    String idempotencyKey = request == null ? null : request.idempotencyKey();
    LifecycleOperation operation = service.requestBackup(currentFingerprint(), idempotencyKey);
    return ResponseEntity.accepted().body(view(operation));
  }

  /** Staff route: read one operation by opaque id (STAFF_CONTROL_LIFECYCLE_READ). */
  @GetMapping(BASE + "/operations/{operationId}")
  public ResponseEntity<OperationView> getOperation(@PathVariable String operationId) {
    return service.findByPublicId(operationId)
        .map(operation -> ResponseEntity.ok(view(operation)))
        .orElseThrow(LifecycleControlException.OperationNotFound::new);
  }

  /** Executor route: lease the next operation (CONTROL_EXECUTOR_LEASE). 204 when none is available. */
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

  /** Executor route: complete a leased operation (CONTROL_EXECUTOR_REPORT). */
  @PostMapping(BASE + "/operations/{operationId}/complete")
  public ResponseEntity<CompletionResponse> complete(
      @PathVariable String operationId,
      @RequestBody(required = false) CompleteRequest request) {
    if (request == null || request.fencingToken() == null) {
      throw new LifecycleControlException.InvalidRequest("FENCING_TOKEN_REQUIRED");
    }
    LifecycleOperationResultCode resultCode = LifecycleOperationResultCode.parse(request.resultCode())
        .orElseThrow(() -> new LifecycleControlException.InvalidRequest("INVALID_RESULT_CODE"));
    LifecycleOperation operation = service.complete(
        currentFingerprint(), operationId, request.fencingToken(), resultCode);
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
    // Idempotency, ownership, fencing, expiry, and conflicting-terminal failures are bounded conflicts.
    return HttpStatus.CONFLICT;
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
