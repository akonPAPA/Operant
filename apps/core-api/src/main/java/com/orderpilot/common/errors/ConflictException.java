package com.orderpilot.common.errors;

/**
 * Raised when a command conflicts with current persistent state (e.g. creating a runtime plan while
 * another plan is already active). Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 */
public class ConflictException extends RuntimeException {
  public ConflictException(String message) {
    super(message);
  }
}
