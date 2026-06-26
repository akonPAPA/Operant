package com.orderpilot.common.errors;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import com.orderpilot.common.tenant.TenantContextMissingException;
import com.orderpilot.common.idempotency.IdempotencyConflictException;
import com.orderpilot.common.idempotency.IdempotencyInProgressException;
import com.orderpilot.application.services.journey.PublicTrackingRateLimitedException;
import com.orderpilot.application.services.runtime.RuntimeLimitException;
import com.orderpilot.application.services.support.DataRepairExecutionException;
import com.orderpilot.application.services.support.ProcessingJobRepairException;
import com.orderpilot.application.services.support.SupportAccessDeniedException;
import com.orderpilot.application.services.workspace.DraftPreparationBlockedException;
import com.orderpilot.application.services.workspace.QuoteLifecycleViolation;
import com.orderpilot.security.ActorVerificationException;
import com.orderpilot.security.policy.TenantPolicyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private final Clock clock;

  public GlobalExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<ApiErrorResponse.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
        .map(this::toViolation)
        .toList();
    return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, violations);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
    return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), request, List.of());
  }

  @ExceptionHandler(DraftPreparationBlockedException.class)
  ResponseEntity<Map<String, Object>> handleDraftPreparationBlocked(DraftPreparationBlockedException ex, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
        "code", "DRAFT_PREPARATION_BLOCKED",
        "message", ex.getMessage(),
        "status", HttpStatus.CONFLICT.value(),
        "path", request.getRequestURI(),
        "timestamp", clock.instant(),
        "blockingReasons", ex.getBlockingReasons()
    ));
  }

  @ExceptionHandler(QuoteLifecycleViolation.class)
  ResponseEntity<Map<String, Object>> handleQuoteLifecycleViolation(QuoteLifecycleViolation ex, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
        "code", "QUOTE_LIFECYCLE_TRANSITION_BLOCKED",
        "message", ex.getMessage(),
        "status", HttpStatus.CONFLICT.value(),
        "path", request.getRequestURI(),
        "timestamp", clock.instant()
    ));
  }

  @ExceptionHandler(RuntimeLimitException.class)
  ResponseEntity<ApiErrorResponse> handleRuntimeLimit(RuntimeLimitException ex, HttpServletRequest request) {
    // OP-CAP-16C: stable runtime guard denials — 403 RUNTIME_QUOTA_EXCEEDED / 429 RUNTIME_RATE_LIMITED.
    HttpStatus status = HttpStatus.valueOf(ex.getHttpStatus());
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
    if (ex.getRetryAfterSeconds() > 0) {
      builder.header("Retry-After", Long.toString(ex.getRetryAfterSeconds()));
    }
    return builder.body(new ApiErrorResponse(
        ex.getErrorCode(),
        ex.getMessage(),
        status.value(),
        request.getRequestURI(),
        clock.instant(),
        List.of()));
  }

  @ExceptionHandler(PublicTrackingRateLimitedException.class)
  ResponseEntity<ApiErrorResponse> handlePublicTrackingRateLimited(
      PublicTrackingRateLimitedException ex, HttpServletRequest request) {
    // Stage 9 public tracking abuse hardening: a per-client over-limit denial. Generic 429 with a
    // Retry-After hint and no token/journey/tenant detail — it never reveals whether the token exists.
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", Long.toString(ex.getRetryAfterSeconds()))
        .body(new ApiErrorResponse(
            "PUBLIC_TRACKING_RATE_LIMITED",
            ex.getMessage(),
            HttpStatus.TOO_MANY_REQUESTS.value(),
            request.getRequestURI(),
            clock.instant(),
            List.of()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException ex, HttpServletRequest request) {
    return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request body is not valid JSON", request, List.of());
  }

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException ex, HttpServletRequest request) {
    return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request, List.of());
  }

  @ExceptionHandler(ConflictException.class)
  ResponseEntity<ApiErrorResponse> handleConflict(ConflictException ex, HttpServletRequest request) {
    return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request, List.of());
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
    return build(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", ex.getMessage(), request, List.of());
  }

  @ExceptionHandler(IdempotencyInProgressException.class)
  ResponseEntity<ApiErrorResponse> handleIdempotencyInProgress(IdempotencyInProgressException ex, HttpServletRequest request) {
    return build(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS", ex.getMessage(), request, List.of());
  }

  @ExceptionHandler(TenantContextMissingException.class)
  ResponseEntity<ApiErrorResponse> handleMissingTenant(TenantContextMissingException ex, HttpServletRequest request) {
    return build(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED", "Missing tenant header X-Tenant-Id", request, List.of());
  }

  @ExceptionHandler(TenantPolicyException.class)
  ResponseEntity<ApiErrorResponse> handleTenantPolicy(TenantPolicyException ex, HttpServletRequest request) {
    return build(HttpStatus.FORBIDDEN, "TENANT_POLICY_DENIED", ex.getMessage(), request, List.of());
  }

  @ExceptionHandler(SupportAccessDeniedException.class)
  ResponseEntity<ApiErrorResponse> handleSupportAccessDenied(SupportAccessDeniedException ex, HttpServletRequest request) {
    // OP-CAP-51: a support access decision failed closed (no grant / expired / wrong tenant / wrong scope /
    // unknown staff principal). Stable, generic 403 — the message never reveals which condition failed.
    return build(HttpStatus.FORBIDDEN, "SUPPORT_ACCESS_DENIED", ex.getMessage(), request, List.of());
  }

  @ExceptionHandler(DataRepairExecutionException.class)
  ResponseEntity<ApiErrorResponse> handleDataRepairExecution(DataRepairExecutionException ex, HttpServletRequest request) {
    // OP-CAP-52: the data-repair execution stub failed closed. Either the approval gate was not satisfied
    // (409 DATA_REPAIR_EXECUTION_DENIED) or the request was approved but execution is disabled in this
    // stage (501 DATA_REPAIR_EXECUTION_DISABLED). No business row is ever read or mutated; the message is
    // safe and reveals no internal/business detail.
    return build(HttpStatus.valueOf(ex.getHttpStatus()), ex.getCode(), ex.getMessage(), request, List.of());
  }

  @ExceptionHandler(ProcessingJobRepairException.class)
  ResponseEntity<ApiErrorResponse> handleProcessingJobRepair(ProcessingJobRepairException ex, HttpServletRequest request) {
    // OP-CAP-54: the bounded processing-job status-repair executor failed closed BEFORE any mutation —
    // either the approval/target gate was not satisfied (PROCESSING_JOB_REPAIR_DENIED) or the deterministic
    // validator refused the repair (PROCESSING_JOB_REPAIR_VALIDATION_FAILED). No business row is mutated;
    // the message is safe and reveals no internal/business detail.
    return build(HttpStatus.valueOf(ex.getHttpStatus()), ex.getCode(), ex.getMessage(), request, List.of());
  }

  @ExceptionHandler(ActorVerificationException.class)
  ResponseEntity<ApiErrorResponse> handleActorVerification(ActorVerificationException ex, HttpServletRequest request) {
    // OP-CAP-16K: signed actor verification failed (missing/invalid/stale signature). Stable 401; the
    // message never contains the expected signature or the signing secret.
    return build(HttpStatus.UNAUTHORIZED, "ACTOR_VERIFICATION_FAILED", ex.getMessage(), request, List.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Unhandled API exception at {}", sanitizeForLog(request.getRequestURI()), ex);
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request, List.of());
  }

  private String sanitizeForLog(String value) {
    if (value == null) {
      return null;
    }
    return value.replace('\r', '_').replace('\n', '_');
  }

  private ApiErrorResponse.FieldViolation toViolation(FieldError error) {
    return new ApiErrorResponse.FieldViolation(error.getField(), error.getDefaultMessage());
  }

  private ResponseEntity<ApiErrorResponse> build(
      HttpStatus status,
      String code,
      String message,
      HttpServletRequest request,
      List<ApiErrorResponse.FieldViolation> violations
  ) {
    return ResponseEntity.status(status).body(new ApiErrorResponse(
        code,
        message,
        status.value(),
        request.getRequestURI(),
        clock.instant(),
        violations
    ));
  }
}
