package com.orderpilot.common.idempotency;

public class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException(String message) {
    super(message);
  }
}
