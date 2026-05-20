package com.orderpilot.common.errors;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import com.orderpilot.security.policy.TenantPolicyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
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

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException ex, HttpServletRequest request) {
    return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request, List.of());
  }

  @ExceptionHandler(TenantPolicyException.class)
  ResponseEntity<ApiErrorResponse> handleTenantPolicy(TenantPolicyException ex, HttpServletRequest request) {
    return build(HttpStatus.FORBIDDEN, "TENANT_POLICY_DENIED", ex.getMessage(), request, List.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request, List.of());
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
